import { readFile } from 'node:fs/promises';
import test, { before, after, beforeEach } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, remove, update } from 'firebase/database';

const base = 'nokaController/v1';
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-noka-controller',
    database: { host: '127.0.0.1', port: 9000, rules: await readFile('firebase/database.rules.json', 'utf8') },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), base + '/devices/deviceA'), {
      meta: { ownerUid: 'ownerA', name: 'Machine A' },
      inbox: { command1: { id: 'command1', action: 'scan', createdAt: Date.now() } },
    });
    await set(ref(context.database(), base + '/operators/ownerA'), true);
    await set(ref(context.database(), base + '/ownerEmail'), 'owner@example.test');
  });
});

test('database root and credential namespace are private', async () => {
  const db = env.unauthenticatedContext().database();
  await assertFails(get(ref(db, '/')));
  await assertFails(get(ref(db, base + '/devices/deviceA')));
});

test('one device cannot read another device or change ownership', async () => {
  const db = env.authenticatedContext('deviceB').database();
  await assertFails(get(ref(db, base + '/devices/deviceA/inbox')));
  await assertFails(set(ref(db, base + '/devices/deviceA/meta'), { ownerUid: 'attacker' }));
});

test('device can read its inbox but cannot create a root command', async () => {
  const db = env.authenticatedContext('deviceA').database();
  await assertSucceeds(get(ref(db, base + '/devices/deviceA/inbox')));
  await assertFails(set(ref(db, base + '/devices/deviceA/inbox/evil'), { action: 'open_all' }));
});

test('owner can send supported commands but neither shell commands nor plaintext credentials', async () => {
  const db = env.authenticatedContext('ownerA', { email: 'operator@example.test' }).database();
  const command = { id: 'newCommand', action: 'scan', createdAt: Date.now(), expiresAt: Date.now() + 120000 };
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/newCommand'), command));
  await assertFails(set(ref(db, base + '/devices/deviceA/inbox/evil'), { ...command, id: 'evil', action: 'shell' }));
  await assertFails(set(ref(db, base + '/devices/deviceA/inbox/evil'), { ...command, id: 'evil', accounts: 'plaintext' }));
});

test('rejoin config and global roblox packages pass rules while invalid bounds fail', async () => {
  const db = env.authenticatedContext('ownerA', { email: 'operator@example.test' }).database();
  const baseCmd = { createdAt: Date.now(), expiresAt: Date.now() + 120000 };
  const config = {
    enabled: true, placeId: '1730877806', jobId: '', checkIntervalSec: 15, delayAfterLaunchSec: 10,
    delayBetweenClonesSec: 15, bootGraceSec: 150, windowGraceSec: 90, disconnectConfirmSec: 10, networkTimeoutSec: 0,
    maxRejoinIntervalSec: 300, retryAttempts: 0, backoffSec: 10, teleportDetection: true, teleportGraceSec: 120,
    rejoinOnCrash: true, detectDisconnectUi: true, maxRamMb: 1200,
  };
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/cfg1'), { ...baseCmd, id: 'cfg1', action: 'set_rejoin_config', config }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/grid1'), { ...baseCmd, id: 'grid1', action: 'grid', rows: 2, cols: 3, gap: 4, top: 24, bottom: 44, smart: true }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/upd1'), { ...baseCmd, id: 'upd1', action: 'self_update' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/login1'), { ...baseCmd, id: 'login1', action: 'login', package: 'premium.nokaA' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/fill1'), { ...baseCmd, id: 'fill1', action: 'fill_login', package: 'premium.nokaA' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/fill2'), { ...baseCmd, id: 'fill2', action: 'fill_login_all' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/full1'), { ...baseCmd, id: 'full1', action: 'fullscreen', package: 'premium.nokaA' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/min1'), { ...baseCmd, id: 'min1', action: 'minimize_all' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/ret1'), { ...baseCmd, id: 'ret1', action: 'retile' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/cls1'), { ...baseCmd, id: 'cls1', action: 'close_all' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/rem1'), { ...baseCmd, id: 'rem1', action: 'remove_profile', package: 'premium.nokaF' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/rst1'), { ...baseCmd, id: 'rst1', action: 'restore_profiles' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/trim1'), { ...baseCmd, id: 'trim1', action: 'trim_ram' }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/opt1'), { ...baseCmd, id: 'opt1', action: 'optimize_ram', fpsCap: 15, lowGraphics: true }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/rej1'), { ...baseCmd, id: 'rej1', action: 'rejoin', package: 'com.roblox.client', force: false }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/inbox/reja1'), { ...baseCmd, id: 'reja1', action: 'rejoin_all', force: true }));
  await assertFails(set(ref(db, base + '/devices/deviceA/inbox/bad1'), { ...baseCmd, id: 'bad1', action: 'rejoin', package: 'com.android.settings' }));
  await assertFails(set(ref(db, base + '/devices/deviceA/inbox/bad2'), { ...baseCmd, id: 'bad2', action: 'set_rejoin_config', config: { ...config, checkIntervalSec: 1 } }));
});

test('paired device can report heartbeat and command result, then acknowledge', async () => {
  const db = env.authenticatedContext('deviceA').database();
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/telemetry'), { lastSeen: Date.now(), model: 'test', android: '12', watch: false }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceA/results/command1'), { id: 'command1', action: 'scan', createdAt: Date.now(), status: 'succeeded' }));
  await assertSucceeds(remove(ref(db, base + '/devices/deviceA/inbox/command1')));
});

test('pairing ticket cannot claim an owner or use a long-lived code', async () => {
  const db = env.authenticatedContext('deviceC').database();
  const registration = { deviceUid: 'deviceC', expiresAt: Date.now() + 600000 };
  const path = base + '/pairingCodes/' + 'a'.repeat(32);
  await assertFails(set(ref(db, path), { ...registration, claimedBy: 'ownerA' }));
  await assertFails(set(ref(db, path), { ...registration, expiresAt: Date.now() + 86400000 }));
  await assertSucceeds(set(ref(db, path), registration));
});

test('pairing requires the correct ticket and ownership cannot be stolen', async () => {
  const code = 'b'.repeat(32);
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), base + '/pairingCodes/' + code), { deviceUid: 'deviceC', expiresAt: Date.now() + 600000 });
    await set(ref(context.database(), base + '/operators/ownerB'), true);
  });
  const db = env.authenticatedContext('ownerA', { email: 'a@example.test' }).database();
  const meta = { ownerUid: 'ownerA', name: 'New', pairedAt: Date.now(), pairingCode: code };
  await assertFails(set(ref(db, base + '/devices/deviceC/meta'), { ...meta, pairingCode: '0'.repeat(32) }));
  await assertSucceeds(set(ref(db, base + '/devices/deviceC/meta'), meta));
  await assertSucceeds(set(ref(db, base + '/owners/ownerA/deviceC'), true));
  const other = env.authenticatedContext('ownerB', { email: 'b@example.test' }).database();
  await assertFails(set(ref(other, base + '/devices/deviceC/meta'), { ...meta, ownerUid: 'ownerB' }));
});

test('encrypted vault and import command can be written atomically, plaintext vault cannot', async () => {
  const db = env.authenticatedContext('ownerA', { email: 'operator@example.test' }).database();
  const command = { id: 'import1', action: 'set_accounts', createdAt: Date.now(), expiresAt: Date.now() + 120000, sealed: { key: 'a2V5', iv: 'aXY=', data: 'ZGF0YQ==' } };
  await assertSucceeds(update(ref(db, base + '/devices/deviceA'), { 'inbox/import1': command, vault: { sealed: command.sealed, updatedAt: Date.now() } }));
  await assertFails(set(ref(db, base + '/devices/deviceA/vault'), { accounts: 'fixture-secret', updatedAt: Date.now() }));
  const other = env.authenticatedContext('deviceB').database();
  await assertFails(get(ref(other, base + '/devices/deviceA/vault')));
});

test('verified owner email bootstraps access but an unverified email cannot', async () => {
  const owner = env.authenticatedContext('googleOwner', { email: 'owner@example.test', email_verified: true }).database();
  await assertSucceeds(get(ref(owner, base + '/ownerEmail')));
  await assertSucceeds(get(ref(owner, base + '/owners/googleOwner')));
  const unverified = env.authenticatedContext('unverified', { email: 'owner@example.test', email_verified: false }).database();
  await assertFails(get(ref(unverified, base + '/owners/unverified')));
  const outsider = env.authenticatedContext('outsider', { email: 'other@example.test', email_verified: true }).database();
  await assertFails(get(ref(outsider, base + '/ownerEmail')));
});

test('owner can rename and delete own device while outsiders and devices cannot', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), base + '/devices/deviceA/meta'), {
      ownerUid: 'ownerA', name: 'Old Name', pairedAt: Date.now(), pairingCode: 'c'.repeat(32),
    });
    await set(ref(context.database(), base + '/owners/ownerA/deviceA'), true);
    await set(ref(context.database(), base + '/operators/ownerB'), true);
  });

  const owner = env.authenticatedContext('ownerA', { email: 'owner@example.test' }).database();
  const other = env.authenticatedContext('ownerB', { email: 'other@example.test' }).database();
  const dev = env.authenticatedContext('deviceA').database();

  await assertFails(remove(ref(other, base + '/devices/deviceA')));
  await assertFails(remove(ref(dev, base + '/devices/deviceA')));
  await assertSucceeds(update(ref(owner, base + '/devices/deviceA/meta'), { name: 'New Name' }));
  await assertSucceeds(remove(ref(owner, base + '/devices/deviceA')));
  await assertSucceeds(remove(ref(owner, base + '/owners/ownerA/deviceA')));
});
