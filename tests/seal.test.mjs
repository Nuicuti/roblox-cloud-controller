import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, relative, join, basename } from 'node:path';
import { spawnSync } from 'node:child_process';
import { sealAccounts } from '../lib/seal.ts';

test('the browser encryption implementation decrypts on the Python agent', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'noka-seal-'));
  try {
    const keypair = await crypto.subtle.generateKey(
      { name: 'RSA-OAEP', hash: 'SHA-256', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
      true,
      ['encrypt', 'decrypt'],
    );
    const publicKey = await crypto.subtle.exportKey('jwk', keypair.publicKey);
    const privateKey = Buffer.from(await crypto.subtle.exportKey('pkcs8', keypair.privateKey));
    const pem =
      '-----BEGIN PRIVATE KEY-----\n' +
      privateKey.toString('base64').match(/.{1,64}/g).join('\n') +
      '\n-----END PRIVATE KEY-----\n';
    const records = [{ package: 'free.nokaA', username: 'fixture-user', password: 'fixture-only', cookie: 'fixture-cookie' }];
    const sealed = await sealAccounts(publicKey, records);
    assert.equal(JSON.stringify(sealed).includes('fixture-cookie'), false);
    await writeFile(join(directory, 'device-key.pem'), pem);
    await writeFile(join(directory, 'sealed.json'), JSON.stringify(sealed));
    const code = [
      'import sys, json',
      'from pathlib import Path',
      'sys.path.insert(0, sys.argv[2])',
      'from agent import Manager',
      'p = Path(sys.argv[1])',
      'm = Manager(p)',
      "m.state['profiles']['free.nokaA'] = {'package': 'free.nokaA', 'label': 'Noka 1', 'username': '', 'slot': 1, 'enabled': True}",
      "m.import_accounts(json.loads((p / 'sealed.json').read_text()))",
      "assert m.accounts['free.nokaA']['cookie'] == 'fixture-cookie'",
      "print('Interop verified')",
    ].join('\n');
    const result = spawnSync('python', ['-X', 'utf8', '-c', code, directory, resolve('agent')], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'Interop verified');
  } finally {
    const within = relative(resolve(tmpdir()), resolve(directory));
    if (!within.startsWith('..') && !within.includes(':') && basename(directory).startsWith('noka-seal-')) {
      await rm(directory, { recursive: true, force: true });
    }
  }
});
