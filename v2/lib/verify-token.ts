import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";

const googleKeys = createRemoteJWKSet(new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"));

export async function verifyFirebaseIdToken(token: string, projectId: string, keys: JWTVerifyGetKey = googleKeys) {
  const { payload } = await jwtVerify(token, keys, {
    algorithms: ["RS256"], issuer: `https://securetoken.google.com/${projectId}`, audience: projectId,
    requiredClaims: ["sub", "iat", "exp", "auth_time"], maxTokenAge: "1h", clockTolerance: 5,
  });
  if (!payload.sub || payload.sub.length > 128 || typeof payload.auth_time !== "number" || payload.auth_time > Date.now()/1000+5)
    throw new Error("Invalid Firebase token claims");
  const firebase = payload.firebase as Record<string, unknown> | undefined;
  if (!firebase || typeof firebase.sign_in_provider !== "string") throw new Error("Missing Firebase provider");
  return { uid: payload.sub, email: typeof payload.email === "string" ? payload.email : undefined,
    email_verified: payload.email_verified === true, firebase: { sign_in_provider: firebase.sign_in_provider } };
}
