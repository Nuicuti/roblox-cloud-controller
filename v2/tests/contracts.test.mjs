import test from 'node:test';
import assert from 'node:assert/strict';
import { commandSchema, ownedBy, isOnline } from '../lib/contracts.ts';

test('accepts supported grid command', () => {
  assert.equal(commandSchema.parse({ action: 'grid', rows: 2, cols: 2, gap: 6, top: 32, bottom: 36 }).cols, 2);
});
test('cannot submit remote shell or an arbitrary app package', () => {
  for (const command of [{action:'shell',command:'id'}, {action:'open',package:'com.android.settings'},
    {action:'close',package:'free.nokaA;id'}, {action:'grid',rows:0,cols:2,gap:6,top:0,bottom:0},
    {action:'set_watch',enabled:'true'}, {action:'scan',extra:'unknown'}]) {
    assert.equal(commandSchema.safeParse(command).success, false);
  }
});
test('ownership is explicit, missing metadata never authorizes a command', () => {
  assert.equal(ownedBy({meta:{ownerUid:'ownerA'}},'ownerB'),false);
  assert.equal(ownedBy(null,'ownerA'),false);
  assert.equal(ownedBy({meta:{ownerUid:'ownerA'}},'ownerA'),true);
});
test('stale and far future heartbeat are not shown as online', () => {
  assert.equal(isOnline(100000,100100),true);
  assert.equal(isOnline(100000,200000),false);
  assert.equal(isOnline(200000,100000),false);
  assert.equal(isOnline(undefined,100000),false);
});
test('credentials must be encrypted, plaintext input is rejected at the API boundary', () => {
  assert.equal(commandSchema.safeParse({action:'set_accounts',accounts:'user:password:cookie'}).success,false);
  assert.equal(commandSchema.safeParse({action:'set_accounts',sealed:{key:'a2V5',iv:'aXY=',data:'ZGF0YQ=='}}).success,true);
});
