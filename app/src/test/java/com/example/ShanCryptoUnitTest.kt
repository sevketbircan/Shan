package com.example

import com.example.crypto.ShanCryptoEngine
import com.example.model.ShanSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class ShanCryptoUnitTest {

    @Test
    fun `valid credentials authenticate and map private nicknames`() {
        val session1 = ShanCryptoEngine.authenticateAndCreateSession("20ss504", "mina504".toCharArray())
        val session2 = ShanCryptoEngine.authenticateAndCreateSession("20bs230", "mina230".toCharArray())

        assertNotNull(session1)
        assertNotNull(session2)
        assertEquals(ShanSlot.USER_1, session1!!.activeSlot)
        assertEquals("Aslı", session1.activeSlot.privateNick)
        assertEquals(ShanSlot.USER_2, session2!!.activeSlot)
        assertEquals("Şevket", session2.activeSlot.privateNick)

        assertNull(ShanCryptoEngine.authenticateAndCreateSession("20ss504", "wrong".toCharArray()))
        assertNull(ShanCryptoEngine.authenticateAndCreateSession("20bs230", "mina504".toCharArray()))
        assertNull(ShanCryptoEngine.authenticateAndCreateSession("unknown", "mina504".toCharArray()))
    }

    @Test
    fun `ecdh p256 and aes 256 gcm round trip succeeds between both slots`() {
        val s1 = ShanCryptoEngine.authenticateAndCreateSession("20ss504", "mina504".toCharArray())!!
        val s2 = ShanCryptoEngine.authenticateAndCreateSession("20bs230", "mina230".toCharArray())!!

        val secretMessage = "Merhaba! Bu mesaj 24 saat sonra silinecek. 🔒"
        val encryptedFrom1 = s1.encryptText(secretMessage, s2.myPublicKeyBase64)
        val decryptedBy2 = s2.decryptText(
            ciphertextBase64 = encryptedFrom1.ciphertextBase64,
            ivBase64 = encryptedFrom1.ivBase64,
            saltBase64 = encryptedFrom1.saltBase64,
            peerPublicKeyBase64 = s1.myPublicKeyBase64
        )
        assertEquals(secretMessage, decryptedBy2)

        // Reverse direction using pre-registered peer public key (null peer override)
        val replyMessage = "Tamamdır, uçtan uca şifreli kanal aktif!"
        val encryptedFrom2 = s2.encryptText(replyMessage, null)
        val decryptedBy1 = s1.decryptText(
            ciphertextBase64 = encryptedFrom2.ciphertextBase64,
            ivBase64 = encryptedFrom2.ivBase64,
            saltBase64 = encryptedFrom2.saltBase64,
            peerPublicKeyBase64 = null
        )
        assertEquals(replyMessage, decryptedBy1)
    }

    @Test
    fun `tampered ciphertext is rejected by aes gcm tag verification`() {
        val s1 = ShanCryptoEngine.authenticateAndCreateSession("20ss504", "mina504".toCharArray())!!
        val s2 = ShanCryptoEngine.authenticateAndCreateSession("20bs230", "mina230".toCharArray())!!

        val enc = s1.encryptText("Gizli veri", s2.myPublicKeyBase64)
        val tamperedB64 = enc.ciphertextBase64.dropLast(2) + "AA"
        var failed = false
        try {
            s2.decryptText(tamperedB64, enc.ivBase64, enc.saltBase64, s1.myPublicKeyBase64)
        } catch (_: AEADBadTagException) {
            failed = true
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
