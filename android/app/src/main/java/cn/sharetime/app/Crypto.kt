package cn.sharetime.app

import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun generate(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        return encode(pair.private.encoded) to encode(pair.public.encoded)
    }
    fun privateKey(text: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(text)))
    private fun key(private: PrivateKey, peer: String, session: String): SecretKeySpec {
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(peer)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(private)
        agreement.doPhase(public, true)
        val secret = agreement.generateSecret()
        // HKDF-SHA256: fixed protocol salt, session-bound info, one output block.
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("ShareTime-v1".toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return SecretKeySpec(mac.doFinal(("record:$session".toByteArray() + byteArrayOf(1))), "AES")
    }
    fun encrypt(data: ByteArray, private: PrivateKey, peer: String, session: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(private, peer, session), GCMParameterSpec(128, nonce))
        cipher.updateAAD(session.toByteArray())
        return encode(nonce + cipher.doFinal(data))
    }
    fun decrypt(body: String, private: PrivateKey, peer: String, session: String): ByteArray {
        val bytes = decode(body)
        require(bytes.size in 29..3_300_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(private, peer, session), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(session.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    fun fingerprint(a: String, b: String): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(a, b).sorted().joinToString(":").toByteArray())
        .take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
}
