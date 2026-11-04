import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, SignJWT } from 'jose';
import { verifyFirebaseIdToken } from '../lib/verify-token.ts';
const keys=await generateKeyPair('RS256');
const now=Math.floor(Date.now()/1000);
async function sign({audience='test-project',issuer='https://securetoken.google.com/test-project',expiration=now+3600,authTime=now,subject='test-user'}={}) {
  return new SignJWT({auth_time:authTime,email:'owner@example.test',email_verified:true,firebase:{sign_in_provider:'google.com'}})
    .setProtectedHeader({alg:'RS256',kid:'fixture'}).setIssuedAt(now).setExpirationTime(expiration)
    .setAudience(audience).setIssuer(issuer).setSubject(subject).sign(keys.privateKey);
}
test('signed Firebase-shaped token accepts only the configured issuer and project',async()=>{
  const valid=await verifyFirebaseIdToken(await sign(),'test-project',async()=>keys.publicKey);
  assert.equal(valid.uid,'test-user');
  for(const changes of [{audience:'other-project'},{issuer:'https://attacker.example'},{expiration:now-30},{authTime:now+3600},{subject:''}])
    await assert.rejects(verifyFirebaseIdToken(await sign(changes),'test-project',async()=>keys.publicKey));
});
test('forged signature is rejected',async()=>{
  const other=await generateKeyPair('RS256');
  await assert.rejects(verifyFirebaseIdToken(await sign(),'test-project',async()=>other.publicKey));
});
