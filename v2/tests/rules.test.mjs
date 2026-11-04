import { readFile } from 'node:fs/promises';
import test, { before, after, beforeEach } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, remove } from 'firebase/database';
import { update } from 'firebase/database';
const base = 'nokaController/v1';
let env;
before(async () => {
  env = await initializeTestEnvironment({projectId:'demo-noka-controller',database:{
    host:'127.0.0.1',port:9000,rules:await readFile('firebase/database.rules.json','utf8')}});
});
after(async () => { await env.cleanup(); });
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(),base+'/devices/deviceA'),{
      meta:{ownerUid:'ownerA',name:'Machine A'},inbox:{command1:{id:'command1',action:'scan',createdAt:Date.now()}}});
    await set(ref(context.database(),base+'/operators/ownerA'),true);
    await set(ref(context.database(),base+'/ownerEmail'),'owner@example.test');
  });
});
test('database root and credential namespace are private', async () => {
  const db=env.unauthenticatedContext().database();
  await assertFails(get(ref(db,'/')));
  await assertFails(get(ref(db,base+'/devices/deviceA')));
});
test('one device cannot read another device or change ownership', async () => {
  const db=env.authenticatedContext('deviceB').database();
  await assertFails(get(ref(db,base+'/devices/deviceA/inbox')));
  await assertFails(set(ref(db,base+'/devices/deviceA/meta'),{ownerUid:'attacker'}));
});
test('device can read its inbox but cannot create a root command', async () => {
  const db=env.authenticatedContext('deviceA').database();
  await assertSucceeds(get(ref(db,base+'/devices/deviceA/inbox')));
  await assertFails(set(ref(db,base+'/devices/deviceA/inbox/evil'),{action:'open_all'}));
});
test('owner can send supported commands but neither shell commands nor plaintext credentials', async () => {
  const db=env.authenticatedContext('ownerA',{email:'operator@example.test'}).database();
  const command={id:'newCommand',action:'scan',createdAt:Date.now(),expiresAt:Date.now()+120000};
  await assertSucceeds(set(ref(db,base+'/devices/deviceA/inbox/newCommand'),command));
  await assertFails(set(ref(db,base+'/devices/deviceA/inbox/evil'),{...command,id:'evil',action:'shell'}));
  await assertFails(set(ref(db,base+'/devices/deviceA/inbox/evil'),{...command,id:'evil',accounts:'plaintext'}));
});
test('paired device can report heartbeat and command result, then acknowledge', async () => {
  const db=env.authenticatedContext('deviceA').database();
  await assertSucceeds(set(ref(db,base+'/devices/deviceA/telemetry'),{lastSeen:Date.now(),model:'test',android:'12',watch:false}));
  await assertSucceeds(set(ref(db,base+'/devices/deviceA/results/command1'),{id:'command1',action:'scan',createdAt:Date.now(),status:'succeeded'}));
  await assertSucceeds(remove(ref(db,base+'/devices/deviceA/inbox/command1')));
});
test('pairing ticket cannot claim an owner or use a long-lived code', async () => {
  const db=env.authenticatedContext('deviceC').database();
  const registration={deviceUid:'deviceC',expiresAt:Date.now()+600000};
  const path=base+'/pairingCodes/'+'a'.repeat(32);
  await assertFails(set(ref(db,path),{...registration,claimedBy:'ownerA'}));
  await assertFails(set(ref(db,path),{...registration,expiresAt:Date.now()+86400000}));
  await assertSucceeds(set(ref(db,path),registration));
});
test('pairing requires the correct ticket and ownership cannot be stolen', async () => {
  const code='b'.repeat(32);
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(),base+'/pairingCodes/'+code),{deviceUid:'deviceC',expiresAt:Date.now()+600000});
    await set(ref(context.database(),base+'/operators/ownerB'),true);
  });
  const db=env.authenticatedContext('ownerA',{email:'a@example.test'}).database();
  const meta={ownerUid:'ownerA',name:'New',pairedAt:Date.now(),pairingCode:code};
  await assertFails(set(ref(db,base+'/devices/deviceC/meta'),{...meta,pairingCode:'0'.repeat(32)}));
  await assertSucceeds(set(ref(db,base+'/devices/deviceC/meta'),meta));
  await assertSucceeds(set(ref(db,base+'/owners/ownerA/deviceC'),true));
  const other=env.authenticatedContext('ownerB',{email:'b@example.test'}).database();
  await assertFails(set(ref(other,base+'/devices/deviceC/meta'),{...meta,ownerUid:'ownerB'}));
});
test('encrypted vault and import command can be written atomically, plaintext vault cannot', async () => {
  const db=env.authenticatedContext('ownerA',{email:'operator@example.test'}).database();
  const command={id:'import1',action:'set_accounts',createdAt:Date.now(),expiresAt:Date.now()+120000,sealed:{key:'a2V5',iv:'aXY=',data:'ZGF0YQ=='}};
  await assertSucceeds(update(ref(db,base+'/devices/deviceA'),{
    'inbox/import1':command,vault:{sealed:command.sealed,updatedAt:Date.now()}}));
  await assertFails(set(ref(db,base+'/devices/deviceA/vault'),{accounts:'fixture-secret',updatedAt:Date.now()}));
  const other=env.authenticatedContext('deviceB').database();
  await assertFails(get(ref(other,base+'/devices/deviceA/vault')));
});
test('verified owner email bootstraps access but an unverified email cannot', async () => {
  const owner=env.authenticatedContext('googleOwner',{email:'owner@example.test',email_verified:true}).database();
  await assertSucceeds(get(ref(owner,base+'/ownerEmail')));
  await assertSucceeds(get(ref(owner,base+'/owners/googleOwner')));
  const unverified=env.authenticatedContext('unverified',{email:'owner@example.test',email_verified:false}).database();
  await assertFails(get(ref(unverified,base+'/owners/unverified')));
  const outsider=env.authenticatedContext('outsider',{email:'other@example.test',email_verified:true}).database();
  await assertFails(get(ref(outsider,base+'/ownerEmail')));
});
