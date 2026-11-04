import base64
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'agent'))
verification_libs = ROOT.parent/'source-review/verification-libs'
if verification_libs.exists():
    sys.path.insert(0,str(verification_libs))
import noka_agent as agent
import cookie_helper

TASKS = '''ACTIVITY MANAGER ACTIVITIES
  * Task{a1 #120 type=standard A=10000:free.nokaA U=0}
    mBounds=Rect(6, 32 - 634, 350)
    windowingMode=freeform
    * Hist #0: ActivityRecord{abc u0 free.nokaA/com.roblox.client.Game t120}
  * Task{a2 #121 type=standard A=10001:free.nokaAB U=0}
    mBounds=Rect(640, 32 - 1274, 350)
    windowingMode=freeform
    * Hist #0: ActivityRecord{def u0 free.nokaAB/com.roblox.client.Game t121}
  mLastPausedActivity: ActivityRecord{old u0 free.nokaB/com.roblox.client.Game t999}
'''

class FakeAndroid:
    def __init__(self):
        self.opened=[]
        self.closed=[]
        self.alive=False
    def packages(self): return ['free.nokaA','free.nokaB','free.nokaC','free.nokaD']
    def running(self, package): return self.alive
    def open(self, package, freeform=False): self.opened.append(package)
    def close(self, package): self.closed.append(package)

class FakeFirebase:
    def __init__(self): self.calls=[]
    def device(self,*args): self.calls.append(args)

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.directory=Path(self.temp.name)
        self.android=FakeAndroid()
        self.manager=agent.Manager(self.directory,self.android)
        self.manager.scan()
    def tearDown(self): self.temp.cleanup()
    def test_real_task_parser_ignores_stale_and_similar_package(self):
        self.assertEqual(agent.parse_tasks(TASKS),{'free.nokaA':120,'free.nokaAB':121})
        self.assertEqual(agent.task_bounds(TASKS,120),((6,32,634,350),True))
        self.assertEqual(agent.task_bounds(TASKS,999),(None,False))
    def test_grid_does_not_overlap_and_rejects_tiny_cells(self):
        cells=agent.grid_bounds(1280,720,2,2,6,32,36)
        self.assertEqual(len(cells),4)
        self.assertLess(cells[0][2],cells[1][0])
        self.assertLess(cells[0][3],cells[2][1])
        with self.assertRaises(agent.AgentError): agent.grid_bounds(360,640,4,4,6,32,36)
    def test_scan_keeps_account_names_and_existing_slot_assignments(self):
        profile=self.manager.state['profiles']['free.nokaA']
        profile['username']='existing-user'
        self.manager.scan()
        self.assertEqual(profile['username'],'existing-user')
        self.assertEqual(profile['slot'],3)
        self.assertEqual(self.manager.state['profiles']['free.nokaC']['slot'],1)
    def test_watchdog_requires_two_missing_readings_and_obeys_cooldown(self):
        for package,p in self.manager.state['profiles'].items(): p['enabled']=package=='free.nokaA'
        self.manager.state['watch']=True
        self.manager.watchdog(100)
        self.assertEqual(self.android.opened,[])
        self.manager.watchdog(103)
        self.assertEqual(self.android.opened,['free.nokaA'])
        self.manager.watchdog(106)
        self.assertEqual(len(self.android.opened),1)
        self.manager.watchdog(134)
        self.assertEqual(len(self.android.opened),2)
    def test_explicit_close_disables_reopen(self):
        self.manager.execute({'action':'close','package':'free.nokaA'})
        self.assertFalse(self.manager.profile('free.nokaA')['enabled'])
        self.assertEqual(self.android.closed,['free.nokaA'])
    def test_slot_change_swaps_windows_without_changing_accounts(self):
        item=dict(self.manager.profile('free.nokaA'))
        item['slot']=1
        item['username']='user-a'
        self.manager.execute({'action':'profile','profile':item})
        self.assertEqual(self.manager.profile('free.nokaA')['slot'],1)
        self.assertEqual(self.manager.profile('free.nokaC')['slot'],3)
        self.assertEqual(self.manager.profile('free.nokaA')['username'],'user-a')
    def test_arbitrary_package_is_rejected_before_android_command(self):
        with self.assertRaises(agent.AgentError): self.manager.execute({'action':'open','package':'com.android.settings'})
        self.assertEqual(self.android.opened,[])
    def test_expired_command_and_replayed_command_never_execute(self):
        fb=FakeFirebase(); journal={}
        command={'id':'12345678-1234-1234-1234-123456789abc','action':'open_all','createdAt':1,'expiresAt':2}
        with patch.object(self.manager,'execute') as execute:
            agent.process_command(fb,self.manager,command,journal)
            agent.process_command(fb,self.manager,command,journal)
            execute.assert_not_called()
        self.assertEqual(journal[command['id']]['status'],'failed')
    def test_success_is_recorded_before_ack_and_not_reexecuted(self):
        fb=FakeFirebase(); journal={}
        command={'id':'12345678-1234-1234-1234-123456789abc','action':'scan','createdAt':1,'expiresAt':9999999999999}
        with patch.object(self.manager,'execute',return_value='OK') as execute:
            agent.process_command(fb,self.manager,command,journal)
            agent.process_command(fb,self.manager,command,journal)
            self.assertEqual(execute.call_count,1)
        self.assertEqual(fb.calls[-1][1],'DELETE')
    def test_hybrid_encryption_roundtrip_and_wrong_device_rejection(self):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        key=self.manager.key()
        records=[{'package':'free.nokaA','username':'fixture-user','password':'fixture-password','cookie':'fixture-cookie'}]
        aes=AESGCM.generate_key(bit_length=256); iv=os.urandom(12)
        wrap=key.public_key().encrypt(aes,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
        encoded=json.dumps(records).encode()
        sealed={k:base64.b64encode(v).decode() for k,v in {'key':wrap,'iv':iv,'data':AESGCM(aes).encrypt(iv,encoded,None)}.items()}
        self.assertNotIn('fixture-cookie',json.dumps(sealed))
        self.manager.import_accounts(sealed)
        self.assertEqual(self.manager.accounts['free.nokaA']['cookie'],'fixture-cookie')
        other=self.directory/'other'; other.mkdir()
        manager=agent.Manager(other,FakeAndroid()); manager.scan()
        with self.assertRaises(agent.AgentError): manager.import_accounts(sealed)
        self.assertFalse((other/'accounts.json').exists())
    def test_cookie_update_preserves_other_domains_and_has_sqlite_backup(self):
        app=self.directory/'free.nokaA/app_webview/Default'
        app.mkdir(parents=True)
        path=app/'Cookies'
        with closing(sqlite3.connect(path)) as db:
            db.execute('CREATE TABLE cookies (host_key TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,path TEXT NOT NULL)')
            db.execute('INSERT INTO cookies VALUES (?,?,?,?)',('other.example','other','keep','/'))
            db.execute('INSERT INTO cookies VALUES (?,?,?,?)',('.roblox.com','.ROBLOSECURITY','old','/'))
            db.commit()
        with patch.object(os,'chown',create=True):
            result=cookie_helper.write_cookie('free.nokaA','new-fixture-cookie',self.directory)
        self.assertTrue(result['ok'])
        with closing(sqlite3.connect(path)) as db:
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE host_key=?',('other.example',)).fetchone()[0],'keep')
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE name=?',('.ROBLOSECURITY',)).fetchone()[0],'new-fixture-cookie')
        with closing(sqlite3.connect(app/'Cookies.noka-backup')) as db:
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE name=?',('.ROBLOSECURITY',)).fetchone()[0],'old')

if __name__=='__main__': unittest.main(verbosity=2)
