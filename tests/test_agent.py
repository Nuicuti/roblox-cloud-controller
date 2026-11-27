import base64
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'agent'))

import agent
import android_os
import cookie_store

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

PROC_NET_UDP = '''  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   12: 0100007F:1234 0100007F:0035 01 00000000:00000000 00:00000000 00000000  10142        0 1001
   13: 0A00020F:9000 00000000:0000 07 00000000:00000000 00:00000000 00000000  10142        0 1002
   14: 0A00020F:9001 01020304:D188 01 00000000:00000000 00:00000000 00000000  10142        0 1003
   15: 0A00020F:9002 05060708:D189 01 00000000:00000000 00:00000000 00000000  0000279f     0 1004
'''


class FakeAndroid:
    def __init__(self):
        self.opened = []
        self.closed = []
        self.alive = False

    def packages(self):
        return ['free.nokaA', 'free.nokaB', 'free.nokaC', 'free.nokaD']

    def running(self, package):
        return self.alive

    def open(self, package, freeform=False, place_id='', job_id=''):
        self.opened.append(package)

    def close(self, package):
        self.closed.append(package)


class FakeFirebase:
    def __init__(self):
        self.calls = []

    def device(self, *args):
        self.calls.append(args)


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.android = FakeAndroid()
        self.manager = agent.Manager(self.directory, self.android)
        self.manager.scan()

    def tearDown(self):
        self.temp.cleanup()

    def test_real_task_parser_and_bounds_match_tolerance(self):
        self.assertEqual(agent.parse_tasks(TASKS), {'free.nokaA': 120, 'free.nokaAB': 121})
        self.assertEqual(agent.task_bounds(TASKS, 120), ((6, 32, 634, 350), True))
        self.assertEqual(agent.task_bounds(TASKS, 999), (None, False))
        self.assertTrue(android_os.bounds_match((6, 32, 634, 350), (6, 32, 634, 350)))
        self.assertTrue(android_os.bounds_match((8, 32, 632, 350), (6, 32, 634, 350)))
        self.assertFalse(android_os.bounds_match((6, 32, 800, 350), (6, 32, 634, 350)))

    def test_proc_net_udp_matches_decimal_and_hex_uid_and_filters_dns_loopback(self):
        self.assertEqual(android_os.parse_proc_net_udp(PROC_NET_UDP, uid=10142), {('01020304', 0xD188)})
        # 0x279f == 10143
        self.assertEqual(android_os.parse_proc_net_udp(PROC_NET_UDP, uid=10143), {('05060708', 0xD189)})

    def test_fast_activity_anr_and_auth_detection(self):
        anr_win = 'Window #1 Window{1 u0 Application Not Responding: free.nokaA}\n'
        self.assertEqual(android_os.inspect_activity_auth_or_anr('', anr_win, 'free.nokaA'), 'disconnected')
        auth_tasks = 'ACTIVITY MANAGER\n  * Hist #0: ActivityRecord{abc u0 free.nokaA/com.roblox.client.ArkoseChallengeActivity t12}\n'
        self.assertEqual(android_os.inspect_activity_auth_or_anr(auth_tasks, '', 'free.nokaA'), 'auth_required')

    def test_grid_does_not_overlap_and_rejects_tiny_cells(self):
        cells = agent.grid_bounds(1280, 720, 2, 3, 6, 32, 36)
        self.assertEqual(len(cells), 6)
        self.assertLess(cells[0][2], cells[1][0])
        self.assertLess(cells[0][3], cells[3][1])
        with self.assertRaises(agent.AgentError):
            agent.grid_bounds(360, 640, 4, 4, 6, 32, 36)

    def test_scan_keeps_account_names_and_existing_slot_assignments(self):
        profile = self.manager.state['profiles']['free.nokaA']
        profile['username'] = 'existing-user'
        self.manager.scan()
        self.assertEqual(profile['username'], 'existing-user')
        self.assertEqual(profile['slot'], 3)
        self.assertEqual(self.manager.state['profiles']['free.nokaC']['slot'], 1)

    def test_watchdog_requires_two_missing_readings_and_obeys_cooldown(self):
        for package, p in self.manager.state['profiles'].items():
            p['enabled'] = package == 'free.nokaA'
        self.manager.state['watch'] = True
        self.manager.watchdog(100)
        self.assertEqual(self.android.opened, [])
        self.manager.watchdog(103)
        self.assertEqual(self.android.opened, ['free.nokaA'])
        self.manager.watchdog(106)
        self.assertEqual(len(self.android.opened), 1)
        self.manager.watchdog(134)
        self.assertEqual(len(self.android.opened), 2)

    def test_explicit_close_disables_reopen(self):
        self.manager.execute({'action': 'close', 'package': 'free.nokaA'})
        self.assertFalse(self.manager.profile('free.nokaA')['enabled'])
        self.assertFalse(self.manager.profile('free.nokaA')['autoReopen'])
        self.assertEqual(self.android.closed, ['free.nokaA'])

    def test_auto_reopen_per_clone_can_be_disabled_independently(self):
        for package, p in self.manager.state['profiles'].items():
            p['enabled'] = package == 'free.nokaA'
        self.manager.state['watch'] = True
        profile = self.manager.state['profiles']['free.nokaA']
        profile['autoReopen'] = False
        self.manager.watchdog(100)
        self.manager.watchdog(103)
        self.assertEqual(self.android.opened, [])
        profile['autoReopen'] = True
        self.manager.watchdog(106)
        self.manager.watchdog(109)
        self.assertEqual(self.android.opened, ['free.nokaA'])

    def test_profile_command_accepts_auto_reopen_and_place_override(self):
        item = dict(self.manager.profile('free.nokaA'))
        item['autoReopen'] = False
        item['placeId'] = '1730877806'
        self.manager.execute({'action': 'profile', 'profile': item})
        self.assertFalse(self.manager.profile('free.nokaA')['autoReopen'])
        self.assertEqual(self.manager.profile('free.nokaA')['placeId'], '1730877806')

    def test_slot_change_swaps_windows_without_changing_accounts(self):
        item = dict(self.manager.profile('free.nokaA'))
        item['slot'] = 1
        item['username'] = 'user-a'
        self.manager.execute({'action': 'profile', 'profile': item})
        self.assertEqual(self.manager.profile('free.nokaA')['slot'], 1)
        self.assertEqual(self.manager.profile('free.nokaC')['slot'], 3)
        self.assertEqual(self.manager.profile('free.nokaA')['username'], 'user-a')

    def test_arbitrary_package_is_rejected_before_android_command(self):
        with self.assertRaises(agent.AgentError):
            self.manager.execute({'action': 'open', 'package': 'com.android.settings'})
        self.assertEqual(self.android.opened, [])

    def test_expired_command_and_replayed_command_never_execute(self):
        fb = FakeFirebase()
        journal = {}
        command = {'id': '12345678-1234-1234-1234-123456789abc', 'action': 'open_all', 'createdAt': 1, 'expiresAt': 2}
        with patch.object(self.manager, 'execute') as execute:
            agent.process_command(fb, self.manager, command, journal)
            agent.process_command(fb, self.manager, command, journal)
            execute.assert_not_called()
        self.assertEqual(journal[command['id']]['status'], 'failed')

    def test_success_is_recorded_before_ack_and_not_reexecuted(self):
        fb = FakeFirebase()
        journal = {}
        command = {'id': '12345678-1234-1234-1234-123456789abc', 'action': 'scan', 'createdAt': 1, 'expiresAt': 9999999999999}
        with patch.object(self.manager, 'execute', return_value='OK') as execute:
            agent.process_command(fb, self.manager, command, journal)
            agent.process_command(fb, self.manager, command, journal)
            self.assertEqual(execute.call_count, 1)
        self.assertEqual(fb.calls[-1][1], 'DELETE')

    def test_hybrid_encryption_roundtrip_and_wrong_device_rejection(self):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        key = self.manager.key()
        records = [{'package': 'free.nokaA', 'username': 'fixture-user', 'password': 'fixture-password', 'cookie': 'fixture-cookie'}]
        aes = AESGCM.generate_key(bit_length=256)
        iv = os.urandom(12)
        wrap = key.public_key().encrypt(aes, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        encoded = json.dumps(records).encode()
        sealed = {k: base64.b64encode(v).decode() for k, v in {'key': wrap, 'iv': iv, 'data': AESGCM(aes).encrypt(iv, encoded, None)}.items()}
        self.assertNotIn('fixture-cookie', json.dumps(sealed))
        self.manager.import_accounts(sealed)
        self.assertEqual(self.manager.accounts['free.nokaA']['cookie'], 'fixture-cookie')
        other = self.directory / 'other'
        other.mkdir()
        manager = agent.Manager(other, FakeAndroid())
        manager.scan()
        with self.assertRaises(agent.AgentError):
            manager.import_accounts(sealed)
        self.assertFalse((other / 'accounts.json').exists())

    def test_cookie_update_preserves_other_domains_and_has_sqlite_backup(self):
        app = self.directory / 'free.nokaA/app_webview/Default'
        app.mkdir(parents=True)
        path = app / 'Cookies'
        with closing(sqlite3.connect(path)) as db:
            db.execute('CREATE TABLE cookies (host_key TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, path TEXT NOT NULL)')
            db.execute('INSERT INTO cookies VALUES (?,?,?,?)', ('other.example', 'other', 'keep', '/'))
            db.execute('INSERT INTO cookies VALUES (?,?,?,?)', ('.roblox.com', '.ROBLOSECURITY', 'old', '/'))
            db.commit()
        with patch.object(os, 'chown', create=True):
            result = cookie_store.write_cookie('free.nokaA', 'new-fixture-cookie', self.directory)
        self.assertTrue(result['ok'])
        with closing(sqlite3.connect(path)) as db:
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE host_key=?', ('other.example',)).fetchone()[0], 'keep')
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE name=?', ('.ROBLOSECURITY',)).fetchone()[0], 'new-fixture-cookie')
        with closing(sqlite3.connect(app / 'Cookies.noka-backup')) as db:
            self.assertEqual(db.execute('SELECT value FROM cookies WHERE name=?', ('.ROBLOSECURITY',)).fetchone()[0], 'old')

    def test_rejoin_config_validation_and_deep_link_uri(self):
        self.assertEqual(agent.build_roblox_uri('1730877806'), 'roblox://experiences/start?placeId=1730877806')
        self.assertEqual(agent.build_roblox_uri('1730877806', 'abc-123'), 'roblox://placeId=1730877806&gameInstanceId=abc-123')
        self.assertEqual(agent.build_roblox_uri(''), '')
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, placeId='1730877806', checkIntervalSec=15, delayBetweenClonesSec=15)
        msg = self.manager.execute({'action': 'set_rejoin_config', 'config': cfg})
        self.assertIn('Place 1730877806', msg)
        self.assertTrue(self.manager.state['watch'])
        with self.assertRaises(agent.AgentError):
            self.manager.execute({'action': 'set_rejoin_config', 'config': dict(cfg, checkIntervalSec=1)})
        with self.assertRaises(agent.AgentError):
            self.manager.execute({'action': 'set_rejoin_config', 'config': dict(cfg, placeId='not-digits')})

    def test_four_layer_window_checks_and_unreadable_dump_protection(self):
        class LayerAndroid:
            def __init__(self):
                self.opened = []
                self.closed = []
                self.tasks_text = 'ACTIVITY MANAGER ACTIVITIES\n'
                self.windows_text = 'WINDOW MANAGER WINDOWS\n  Window #1 Window{123 u0 com.android.launcher/Launcher}\n'
                self.surface_text = 'SurfaceFlinger\nStatusBar#0\n'
                self.xml = '<hierarchy rotation="0"><node package="com.android.launcher" text="Home" /></hierarchy>'
            def packages(self): return ['free.nokaA', 'free.nokaB']
            def running(self, pkg): return True
            def tasks(self): return self.tasks_text
            def windows(self): return self.windows_text
            def surfaces(self): return self.surface_text
            def ui_tree(self): return self.xml
            def udp_peers(self, pkg): return set()
            def window_checks(self, pkg, ui_xml=None, tasks_text=None, windows_text=None, surface_text=None):
                return (
                    agent.check_task_list(self.tasks_text if tasks_text is None else tasks_text, pkg),
                    agent.check_window_manager(self.windows_text if windows_text is None else windows_text, pkg),
                    agent.check_surfaces(self.surface_text if surface_text is None else surface_text, pkg),
                    agent.check_screen_tree(self.xml if ui_xml is None else ui_xml, pkg),
                )
            def open(self, pkg, freeform=False, place_id='', job_id=''): self.opened.append((pkg, place_id, job_id))
            def close(self, pkg): self.closed.append(pkg)

        la = LayerAndroid()
        mgr = agent.Manager(self.directory / 'layers', la)
        mgr.scan()
        mgr.state['profiles']['free.nokaB']['enabled'] = False
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, placeId='1730877806', checkIntervalSec=5,
                   delayAfterLaunchSec=5, bootGraceSec=10, windowGraceSec=15, backoffSec=5)
        mgr.execute({'action': 'set_rejoin_config', 'config': cfg})

        la.xml = ''
        mgr.watchdog(100)
        mgr.watchdog(150)
        self.assertEqual(la.opened, [])

        la.xml = '<hierarchy rotation="0"><node package="com.android.launcher" text="Home" /></hierarchy>'
        mgr.watchdog(200)
        self.assertEqual(la.opened, [])
        mgr.watchdog(210)
        self.assertEqual(la.opened, [])
        mgr.watchdog(215)
        self.assertEqual(la.opened, [('free.nokaA', '1730877806', '')])
        self.assertEqual(la.closed, ['free.nokaA'])

    def test_minimized_live_udp_reopens_without_force_stop(self):
        class LiveUdpAndroid:
            def __init__(self):
                self.opened = []
                self.closed = []
            def packages(self): return ['free.nokaA']
            def running(self, pkg): return True
            def tasks(self): return 'ACTIVITY MANAGER ACTIVITIES\n'
            def windows(self): return 'WINDOW MANAGER WINDOWS\n  Window #1 Window{1 u0 com.android.launcher/Home}\n'
            def surfaces(self): return 'SurfaceFlinger\nStatusBar#0\n'
            def ui_tree(self): return '<hierarchy><node package="com.android.launcher" text="Home" /></hierarchy>'
            def udp_peers(self, pkg): return {('01020304', 53640)}
            def window_checks(self, pkg, **kw): return (False, False, False, False)
            def open(self, pkg, freeform=False, place_id='', job_id=''): self.opened.append((pkg, place_id))
            def close(self, pkg): self.closed.append(pkg)

        ua = LiveUdpAndroid()
        mgr = agent.Manager(self.directory / 'liveudp', ua)
        mgr.scan()
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, placeId='1730877806', checkIntervalSec=5,
                   windowGraceSec=10, networkTimeoutSec=30, backoffSec=5)
        mgr.execute({'action': 'set_rejoin_config', 'config': cfg})
        mgr.watchdog(100)
        mgr.watchdog(112)
        self.assertEqual(ua.opened, [('free.nokaA', '1730877806')])
        self.assertEqual(ua.closed, [])

    def test_disconnect_ui_confirmed_and_suppressed_by_teleport_grace(self):
        class UiAndroid:
            def __init__(self):
                self.opened = []
                self.closed = []
                self.xml = '<hierarchy><node package="free.nokaA" text="Playing" /></hierarchy>'
                self.peers = {('0A000001', 50000)}
            def packages(self): return ['free.nokaA']
            def running(self, pkg): return True
            def tasks(self): return 'ACTIVITY MANAGER\n  * Hist #0: ActivityRecord{a u0 free.nokaA/com.roblox.client.Game t1}\n'
            def windows(self): return 'WINDOW MANAGER\n  Window #0 Window{1 u0 free.nokaA/com.roblox.client.Game}\n'
            def surfaces(self): return 'SurfaceFlinger\nfree.nokaA/com.roblox.client.Game#0\n'
            def ui_tree(self): return self.xml
            def udp_peers(self, pkg): return self.peers
            def window_checks(self, pkg, **kw): return (True, True, True, True)
            def open(self, pkg, freeform=False, place_id='', job_id=''): self.opened.append((pkg, place_id))
            def close(self, pkg): self.closed.append(pkg)

        ua = UiAndroid()
        mgr = agent.Manager(self.directory / 'ui', ua)
        mgr.scan()
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, placeId='1730877806', checkIntervalSec=5,
                   disconnectConfirmSec=10, teleportDetection=True, teleportGraceSec=60, backoffSec=5)
        mgr.execute({'action': 'set_rejoin_config', 'config': cfg})

        mgr.watchdog(100)
        ua.peers = {('0A000002', 50001)}
        ua.xml = '<hierarchy><node package="free.nokaA" text="Disconnected" /></hierarchy>'
        mgr.watchdog(106)
        mgr.watchdog(120)
        self.assertEqual(ua.opened, [])

        mgr.watchdog(170)
        self.assertEqual(ua.opened, [])
        mgr.watchdog(180)
        self.assertEqual(ua.opened, [('free.nokaA', '1730877806')])
        self.assertEqual(ua.closed, ['free.nokaA'])

    def test_auth_or_captcha_ui_pauses_rejoin_for_manual_action(self):
        class AuthAndroid:
            def __init__(self):
                self.opened = []
                self.closed = []
            def packages(self): return ['free.nokaA']
            def running(self, pkg): return True
            def tasks(self): return 'ACTIVITY MANAGER\n  * Hist #0: ActivityRecord{a u0 free.nokaA/com.roblox.client.Game t1}\n'
            def windows(self): return 'WINDOW MANAGER\n  Window #0 Window{1 u0 free.nokaA/com.roblox.client.Game}\n'
            def surfaces(self): return 'SurfaceFlinger\nfree.nokaA/com.roblox.client.Game#0\n'
            def ui_tree(self): return '<hierarchy><node package="free.nokaA" text="Verify you are human" /><node package="free.nokaA" text="Arkose" /></hierarchy>'
            def window_checks(self, pkg, **kw): return (True, True, True, True)
            def open(self, pkg, freeform=False, place_id='', job_id=''): self.opened.append(pkg)
            def close(self, pkg): self.closed.append(pkg)

        aa = AuthAndroid()
        mgr = agent.Manager(self.directory / 'auth', aa)
        mgr.scan()
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, checkIntervalSec=5, bootGraceSec=10)
        mgr.execute({'action': 'set_rejoin_config', 'config': cfg})
        mgr.watchdog(100)
        mgr.watchdog(130)
        self.assertEqual(aa.opened, [])
        self.assertEqual(aa.closed, [])
        self.assertEqual(mgr.profile('free.nokaA').get('healthState'), 'auth_required')
        self.assertEqual(mgr.profile('free.nokaA').get('loginStatus'), 'manual_verification_required')

    def test_staggered_nonblocking_queue_and_retry_limit_and_scan_six(self):
        class MultiAndroid:
            def __init__(self):
                self.opened = []
                self.closed = []
            def packages(self):
                return ['com.roblox.client', 'free.nokaA', 'free.nokaB', 'free.nokaC', 'free.nokaD', 'free.nokaE', 'free.nokaF']
            def running(self, pkg): return False
            def open(self, pkg, freeform=False, place_id='', job_id=''): self.opened.append((pkg, place_id))
            def close(self, pkg): self.closed.append(pkg)

        ma = MultiAndroid()
        mgr = agent.Manager(self.directory / 'multi', ma)
        mgr.scan()
        self.assertIn('com.roblox.client', mgr.state['profiles'])
        self.assertIn('free.nokaF', mgr.state['profiles'])
        for pkg, p in mgr.state['profiles'].items():
            p['enabled'] = pkg in ('free.nokaA', 'free.nokaB')
        cfg = dict(agent.DEFAULT_REJOIN_CONFIG, enabled=True, placeId='999', checkIntervalSec=5,
                   delayAfterLaunchSec=5, delayBetweenClonesSec=15, retryAttempts=1, backoffSec=5)
        mgr.execute({'action': 'set_rejoin_config', 'config': cfg})

        mgr.watchdog(100)
        self.assertEqual(ma.opened, [])
        mgr.watchdog(105)
        self.assertEqual(ma.opened, [('free.nokaA', '999')])
        mgr.watchdog(110)
        self.assertEqual(ma.opened, [('free.nokaA', '999')])
        mgr.watchdog(120)
        self.assertEqual(ma.opened, [('free.nokaA', '999'), ('free.nokaB', '999')])
        mgr.watchdog(150)
        self.assertEqual(len(ma.opened), 2)
        self.assertEqual(mgr.profile('free.nokaA').get('healthState'), 'paused_retries')

        # Non-blocking staggered rejoin_all queues both clones; drain_launch_queue launches one per delayBetweenClonesSec.
        ma.opened.clear()
        with patch('agent.time.monotonic', return_value=200):
            msg = mgr.execute({'action': 'rejoin_all'})
        self.assertIn('tuần tự', msg)
        self.assertEqual(ma.opened, [('free.nokaA', '999')])
        self.assertFalse(mgr.drain_launch_queue(210))
        self.assertTrue(mgr.drain_launch_queue(215))
        self.assertEqual(ma.opened, [('free.nokaA', '999'), ('free.nokaB', '999')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
