import test from 'node:test';
import assert from 'node:assert/strict';
import { commandSchema, DEFAULT_REJOIN_CONFIG, ownedBy, isOnline, suggestGrid } from '../lib/contracts.ts';

test('accepts supported grid command and smart preset helper', () => {
  assert.equal(commandSchema.parse({ action: 'grid', rows: 2, cols: 3, gap: 6, top: 32, bottom: 36 }).cols, 3);
  assert.equal(commandSchema.parse({ action: 'grid', rows: 2, cols: 3, gap: 4, top: 24, bottom: 44, smart: true }).smart, true);
  assert.deepEqual(suggestGrid(6, 1280, 720), { rows: 2, cols: 3, gap: 4, top: 24, bottom: 44 });
  assert.deepEqual(suggestGrid(6, 720, 1280), { rows: 3, cols: 2, gap: 4, top: 24, bottom: 44 });
  assert.deepEqual(suggestGrid(4, 1280, 720), { rows: 2, cols: 2, gap: 4, top: 24, bottom: 44 });
});

test('accepts rejoin config, rejoin commands, and global roblox / 6 clones', () => {
  assert.equal(commandSchema.parse({ action: 'set_rejoin_config', config: { ...DEFAULT_REJOIN_CONFIG, placeId: '1730877806' } }).config.placeId, '1730877806');
  assert.equal(commandSchema.parse({ action: 'rejoin', package: 'com.roblox.client' }).package, 'com.roblox.client');
  assert.equal(commandSchema.parse({ action: 'open', package: 'free.nokaF' }).package, 'free.nokaF');
  assert.equal(commandSchema.parse({ action: 'open', package: 'premium.nokaA' }).package, 'premium.nokaA');
  assert.equal(commandSchema.parse({ action: 'open', package: 'com.noka.free1' }).package, 'com.noka.free1');
  assert.equal(commandSchema.parse({ action: 'open', package: 'com.roblox.clien1' }).package, 'com.roblox.clien1');
  assert.equal(commandSchema.parse({ action: 'rejoin_all' }).action, 'rejoin_all');
  assert.equal(commandSchema.parse({ action: 'fill_login', package: 'premium.nokaA' }).action, 'fill_login');
  assert.equal(commandSchema.parse({ action: 'fill_login_all' }).action, 'fill_login_all');
  assert.equal(commandSchema.parse({ action: 'self_update' }).action, 'self_update');
});

test('cannot submit remote shell or an arbitrary app package', () => {
  for (const command of [
    { action: 'shell', command: 'id' },
    { action: 'open', package: 'com.android.settings' },
    { action: 'close', package: 'free.nokaA;id' },
    { action: 'grid', rows: 0, cols: 2, gap: 6, top: 0, bottom: 0 },
    { action: 'set_watch', enabled: 'true' },
    { action: 'scan', extra: 'unknown' },
    { action: 'set_rejoin_config', config: { ...DEFAULT_REJOIN_CONFIG, placeId: 'abc' } },
    { action: 'set_rejoin_config', config: { ...DEFAULT_REJOIN_CONFIG, checkIntervalSec: 1 } },
  ]) {
    assert.equal(commandSchema.safeParse(command).success, false);
  }
});

test('ownership is explicit, missing metadata never authorizes a command', () => {
  assert.equal(ownedBy({ meta: { ownerUid: 'ownerA' } }, 'ownerB'), false);
  assert.equal(ownedBy(null, 'ownerA'), false);
  assert.equal(ownedBy({ meta: { ownerUid: 'ownerA' } }, 'ownerA'), true);
});

test('stale and far future heartbeat are not shown as online', () => {
  assert.equal(isOnline(100000, 100100), true);
  assert.equal(isOnline(100000, 200000), false);
  assert.equal(isOnline(200000, 100000), false);
  assert.equal(isOnline(undefined, 100000), false);
});

test('credentials must be encrypted, plaintext input is rejected at the API boundary', () => {
  assert.equal(commandSchema.safeParse({ action: 'set_accounts', accounts: 'user:password:cookie' }).success, false);
  assert.equal(commandSchema.safeParse({ action: 'set_accounts', sealed: { key: 'a2V5', iv: 'aXY=', data: 'ZGF0YQ==' } }).success, true);
});
