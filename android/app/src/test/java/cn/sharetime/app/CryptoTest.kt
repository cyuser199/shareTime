package cn.sharetime.app

import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    @Test fun bothDevicesDecryptWithFreshNonces() {
        val a = Crypto.generate(); val b = Crypto.generate()
        val data = "a captured moment".toByteArray()
        val one = Crypto.encrypt(data, Crypto.privateKey(a.first), b.second, "round-1")
        val two = Crypto.encrypt(data, Crypto.privateKey(a.first), b.second, "round-1")
        assertNotEquals(one, two)
        assertArrayEquals(data, Crypto.decrypt(one, Crypto.privateKey(b.first), a.second, "round-1"))
        assertEquals(Crypto.fingerprint(a.second, b.second), Crypto.fingerprint(b.second, a.second))
    }
    @Test fun wrongRoundAndTamperingFailAuthentication() {
        val a = Crypto.generate(); val b = Crypto.generate()
        val encrypted = Crypto.encrypt("secret".toByteArray(), Crypto.privateKey(a.first), b.second, "one")
        try { Crypto.decrypt(encrypted, Crypto.privateKey(b.first), a.second, "two"); fail("wrong round accepted") } catch (_: javax.crypto.AEADBadTagException) { }
        val bytes = Crypto.decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try { Crypto.decrypt(Crypto.encode(bytes), Crypto.privateKey(b.first), a.second, "one"); fail("tamper accepted") } catch (_: javax.crypto.AEADBadTagException) { }
    }
}
