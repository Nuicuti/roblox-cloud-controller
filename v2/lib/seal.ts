"use client";
// RSA-OAEP wraps a new AES-GCM key for each submission. Only the device has the private key.
export async function sealAccounts(publicKey: JsonWebKey, records: unknown) {
  const rsa = await crypto.subtle.importKey("jwk", publicKey,
    { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"]);
  const aes = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(JSON.stringify(records));
  if (encoded.length > 120000) throw new Error("Danh sách tài khoản quá dài.");
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aes, encoded);
  const key = await crypto.subtle.encrypt("RSA-OAEP", rsa, await crypto.subtle.exportKey("raw", aes));
  const b64 = (value: ArrayBuffer | Uint8Array) => {
    const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary);
  };
  return { key: b64(key), iv: b64(iv), data: b64(data) };
}
