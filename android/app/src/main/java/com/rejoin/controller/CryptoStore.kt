package com.rejoin.controller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class CryptoStore(private val storageDir: File) {
    private var cachedKeyPair: KeyPair? = null

    fun ensureKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        storageDir.mkdirs()
        val privFile = File(storageDir, "device_priv.der")
        val pubFile = File(storageDir, "device_pub.der")
        val kp = if (privFile.exists() && pubFile.exists()) {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
            val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
            KeyPair(pub, priv)
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val created = gen.generateKeyPair()
            privFile.writeBytes(created.private.encoded)
            pubFile.writeBytes(created.public.encoded)
            created
        }
        cachedKeyPair = kp
        return kp
    }

    fun publicJwk(): JSONObject {
        val pub = ensureKeyPair().public as RSAPublicKey
        return JSONObject().apply {
            put("kty", "RSA")
            put("n", b64UrlUnsigned(pub.modulus))
            put("e", b64UrlUnsigned(pub.publicExponent))
            put("alg", "RSA-OAEP-256")
            put("ext", true)
            put("key_ops", JSONArray(listOf("encrypt")))
        }
    }

    fun decryptAccounts(sealed: JSONObject): List<AccountRecord> {
        val kp = ensureKeyPair()
        val decoder = Base64.getDecoder()
        val wrappedKey = decoder.decode(sealed.getString("key"))
        val iv = decoder.decode(sealed.getString("iv"))
        val cipherData = decoder.decode(sealed.getString("data"))

        // Explicit SHA-256 for both digest and MGF1 to match WebCrypto RSA-OAEP SHA-256.
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        rsaCipher.init(Cipher.DECRYPT_MODE, kp.private, oaepSpec)
        val aesKeyBytes = rsaCipher.doFinal(wrappedKey)

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
        val plainBytes = aesCipher.doFinal(cipherData)
        if (plainBytes.size > 120_000) throw AgentException("Dữ liệu giải mã vượt quá giới hạn.")

        val arr = JSONArray(String(plainBytes, Charsets.UTF_8))
        if (arr.length() !in 1..36) throw AgentException("Số lượng tài khoản không hợp lệ.")
        val list = mutableListOf<AccountRecord>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val keys = item.keys().asSequence().toSet()
            if (keys != setOf("package", "username", "password", "cookie")) {
                throw AgentException("Định dạng tài khoản không hợp lệ.")
            }
            val pkg = item.getString("package")
            val user = item.getString("username")
            val pass = item.getString("password")
            val cookie = item.getString("cookie")
            if (!ConfigLimits.isAllowedPackage(pkg) ||
                user.length !in 1..80 ||
                cookie.length > 20000 ||
                pass.length > 200 ||
                (cookie.isEmpty() && pass.isEmpty())
            ) {
                throw AgentException("Thông tin tài khoản ngoài giới hạn cho phép.")
            }
            list.add(AccountRecord(pkg, user, pass, cookie))
        }
        return list
    }

    private fun b64UrlUnsigned(value: BigInteger): String {
        val raw = value.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
    }
}
