package com.example.crypto

import com.example.model.ShanSlot
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private object ShanBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val hasB1 = i < data.size
            val b1 = if (hasB1) data[i++].toInt() and 0xFF else 0
            val hasB2 = i < data.size
            val b2 = if (hasB2) data[i++].toInt() and 0xFF else 0

            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (hasB1) ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (hasB2) ALPHABET[b2 and 0x3F] else '=')
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val clean = input.trim().filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var i = 0
        while (i + 3 < clean.length) {
            val c0 = DECODE_TABLE[clean[i].code]
            val c1 = DECODE_TABLE[clean[i + 1].code]
            val c2Char = clean[i + 2]
            val c3Char = clean[i + 3]
            val c2 = if (c2Char == '=') 0 else DECODE_TABLE[c2Char.code]
            val c3 = if (c3Char == '=') 0 else DECODE_TABLE[c3Char.code]
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) {
                throw IllegalArgumentException("Invalid Base64 character")
            }
            out.add(((c0 shl 2) or (c1 shr 4)).toByte())
            if (c2Char != '=') out.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())
            if (c3Char != '=') out.add((((c2 and 0x03) shl 6) or c3).toByte())
            i += 4
        }
        return out.toByteArray()
    }
}

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String,
    val saltBase64: String
)

data class ShanSessionCrypto(
    val activeSlot: ShanSlot,
    val myPublicKeyBase64: String,
    val myFingerprint: String,
    private val myPrivateKey: PrivateKey,
    private val defaultPeerPublicKey: PublicKey
) {
    private val secureRandom = SecureRandom()

    fun encryptText(
        plaintext: String,
        peerPublicKeyBase64: String? = null
    ): EncryptedPayload {
        return encryptBytes(plaintext.toByteArray(StandardCharsets.UTF_8), peerPublicKeyBase64)
    }

    fun decryptText(
        ciphertextBase64: String,
        ivBase64: String,
        saltBase64: String,
        peerPublicKeyBase64: String? = null
    ): String {
        val bytes = decryptBytes(ciphertextBase64, ivBase64, saltBase64, peerPublicKeyBase64)
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun encryptBytes(
        plainBytes: ByteArray,
        peerPublicKeyBase64: String? = null
    ): EncryptedPayload {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aesKey = deriveMessageAesKey(salt, peerPublicKeyBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainBytes)
        return EncryptedPayload(
            ciphertextBase64 = ShanBase64.encode(encrypted),
            ivBase64 = ShanBase64.encode(iv),
            saltBase64 = ShanBase64.encode(salt)
        )
    }

    fun decryptBytes(
        ciphertextBase64: String,
        ivBase64: String,
        saltBase64: String,
        peerPublicKeyBase64: String? = null
    ): ByteArray {
        val ciphertext = ShanBase64.decode(ciphertextBase64)
        val iv = ShanBase64.decode(ivBase64)
        val salt = ShanBase64.decode(saltBase64)
        val aesKey = deriveMessageAesKey(salt, peerPublicKeyBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveMessageAesKey(
        salt: ByteArray,
        peerPublicKeyBase64: String?
    ): SecretKeySpec {
        val peerPubKey = if (!peerPublicKeyBase64.isNullOrBlank()) {
            ShanCryptoEngine.decodePublicKey(peerPublicKeyBase64) ?: defaultPeerPublicKey
        } else {
            defaultPeerPublicKey
        }
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPubKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val keyBytes = ShanCryptoEngine.hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = "shan.e2ee.aes256gcm.v1".toByteArray(StandardCharsets.UTF_8),
            lengthBytes = 32
        )
        return SecretKeySpec(keyBytes, "AES")
    }
}

object ShanCryptoEngine {

    // Salted SHA-256 credential digests; plaintext passwords are never stored in source or on disk.
    private val SLOT_1_DIGEST = byteArrayOf(
        0x8e.toByte(), 0x78.toByte(), 0x2e.toByte(), 0xe5.toByte(),
        0x9d.toByte(), 0xe9.toByte(), 0xf7.toByte(), 0x1e.toByte(),
        0x86.toByte(), 0x17.toByte(), 0x8d.toByte(), 0xbb.toByte(),
        0x3a.toByte(), 0x1e.toByte(), 0xd2.toByte(), 0x8e.toByte(),
        0xc5.toByte(), 0xae.toByte(), 0x81.toByte(), 0x90.toByte(),
        0xbf.toByte(), 0x1c.toByte(), 0x1c.toByte(), 0x27.toByte(),
        0x01.toByte(), 0x67.toByte(), 0xbb.toByte(), 0xec.toByte(),
        0xa8.toByte(), 0xcb.toByte(), 0xf6.toByte(), 0x88.toByte()
    )

    private val SLOT_2_DIGEST = byteArrayOf(
        0x3f.toByte(), 0x0b.toByte(), 0x03.toByte(), 0x5f.toByte(),
        0x4a.toByte(), 0x73.toByte(), 0x8c.toByte(), 0xce.toByte(),
        0x03.toByte(), 0x8e.toByte(), 0x52.toByte(), 0x0d.toByte(),
        0xc7.toByte(), 0x67.toByte(), 0x30.toByte(), 0xee.toByte(),
        0x4b.toByte(), 0xfa.toByte(), 0x37.toByte(), 0xdb.toByte(),
        0xf9.toByte(), 0xc2.toByte(), 0x24.toByte(), 0x68.toByte(),
        0x76.toByte(), 0x8b.toByte(), 0x3d.toByte(), 0x0f.toByte(),
        0xf2.toByte(), 0xbf.toByte(), 0x92.toByte(), 0x00.toByte()
    )

    // NIST P-256 (secp256r1) standard curve parameters (FIPS 186-4)
    private val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val P256_A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val P256_GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val P256_GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val P256_N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val P256_G = ECPoint(P256_GX, P256_GY)

    // Fixed ASN.1 DER prefixes for secp256r1 PKCS#8 PrivateKeyInfo and X.509 SubjectPublicKeyInfo
    private val P256_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x41, 0x02, 0x01, 0x00, 0x30, 0x13, 0x06, 0x07,
        0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        0x04, 0x27, 0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20
    )

    private val P256_X509_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07,
        0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        0x03, 0x42, 0x00, 0x04
    )

    private const val SLOT_1_PUB_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZnWkGeoGk48CKQEeE3wGC1bcuKtAfxnoGK9RGBc58iBY6hEa3Y9whIRon57itvtZ4lrusHrgYymAYCzTf0K8Yg=="
    private const val SLOT_2_PUB_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEoY3XGZ6Sjjjw17llngsZ+p7JqhJ5ImnGflYlkWBXl037UXYh34q217eHBQhrBi285+EqjjpYa5xye0mJT6g4Zw=="

    fun authenticateAndCreateSession(username: String, passwordChars: CharArray): ShanSessionCrypto? {
        val slot = ShanSlot.fromSlotId(username) ?: return null
        val passwordStr = String(passwordChars)
        val inputHash = sha256("shan.vault.auth.v1:${slot.slotId}:$passwordStr".toByteArray(StandardCharsets.UTF_8))
        val expectedHash = when (slot) {
            ShanSlot.USER_1 -> SLOT_1_DIGEST
            ShanSlot.USER_2 -> SLOT_2_DIGEST
        }
        if (!MessageDigest.isEqual(inputHash, expectedHash)) {
            return null
        }

        val (myPriv, myPub) = deriveEcdhKeyPair(slot, passwordChars)
        val peerSlot = slot.peerSlot()
        val peerPubB64 = when (peerSlot) {
            ShanSlot.USER_1 -> SLOT_1_PUB_B64
            ShanSlot.USER_2 -> SLOT_2_PUB_B64
        }
        val peerPub = decodePublicKey(peerPubB64)
            ?: error("Failed to decode peer ECDH public key")

        val myPubB64 = ShanBase64.encode(myPub.encoded)
        return ShanSessionCrypto(
            activeSlot = slot,
            myPublicKeyBase64 = myPubB64,
            myFingerprint = computeFingerprint(myPub.encoded),
            myPrivateKey = myPriv,
            defaultPeerPublicKey = peerPub
        )
    }

    internal fun deriveEcdhKeyPair(slot: ShanSlot, passwordChars: CharArray): Pair<PrivateKey, PublicKey> {
        val salt = "shan.ecdh.secp256r1.${slot.slotId}".toByteArray(StandardCharsets.UTF_8)
        val rawBytes = pbkdf2HmacSha256(passwordChars, salt, iterations = 20_000)
        val scalar = BigInteger(1, rawBytes).mod(P256_N.subtract(BigInteger.ONE)).add(BigInteger.ONE)

        val pubPoint = scalarMultiply(P256_G, scalar)
        val pkcs8Bytes = P256_PKCS8_PREFIX + toFixed32Bytes(scalar)
        val x509Bytes = P256_X509_PREFIX + toFixed32Bytes(pubPoint.affineX) + toFixed32Bytes(pubPoint.affineY)

        val keyFactory = KeyFactory.getInstance("EC")
        val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
        val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))
        return privKey to pubKey
    }

    private fun pbkdf2HmacSha256(passwordChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val passBytes = String(passwordChars).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passBytes, "HmacSHA256"))
        passBytes.fill(0)

        val blockInput = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, blockInput, 0, salt.size)
        blockInput[salt.size] = 0
        blockInput[salt.size + 1] = 0
        blockInput[salt.size + 2] = 0
        blockInput[salt.size + 3] = 1

        var u = mac.doFinal(blockInput)
        val result = u.copyOf()
        for (iter in 2..iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    private fun toFixed32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    fun decodePublicKey(base64: String): PublicKey? = try {
        val bytes = ShanBase64.decode(base64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey
    } catch (_: Exception) {
        null
    }

    fun computeFingerprint(keyBytes: ByteArray): String {
        val digest = sha256(keyBytes)
        return digest.take(12).joinToString(":") { "%02X".format(it) }
    }

    fun computeFingerprintFromBase64(publicKeyBase64: String): String {
        if (publicKeyBase64.isBlank()) return "Bekleniyor"
        return try {
            computeFingerprint(ShanBase64.decode(publicKeyBase64))
        } catch (_: Exception) {
            "Doğrulanamadı"
        }
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lengthBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(lengthBytes)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < lengthBytes) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, lengthBytes - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    internal fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    // Pure BigInteger Weierstrass elliptic curve arithmetic on NIST P-256 (secp256r1)
    private fun scalarMultiply(g: ECPoint, k: BigInteger): ECPoint {
        val p = P256_P
        val a = P256_A
        var result: ECPoint = ECPoint.POINT_INFINITY
        var addend: ECPoint = g
        var scalar = k
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) {
                result = pointAdd(result, addend, p, a)
            }
            addend = pointDouble(addend, p, a)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    private fun pointAdd(p1: ECPoint, p2: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        if (p1.affineX == p2.affineX) {
            return if (p1.affineY == p2.affineY) pointDouble(p1, p, a)
            else ECPoint.POINT_INFINITY
        }
        val dy = p2.affineY.subtract(p1.affineY).mod(p)
        val dx = p2.affineX.subtract(p1.affineX).mod(p)
        val lambda = dy.multiply(dx.modInverse(p)).mod(p)
        val rx = lambda.multiply(lambda).subtract(p1.affineX).subtract(p2.affineX).mod(p)
        val ry = lambda.multiply(p1.affineX.subtract(rx)).subtract(p1.affineY).mod(p)
        return ECPoint(rx, ry)
    }

    private fun pointDouble(pt: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (pt == ECPoint.POINT_INFINITY || pt.affineY == BigInteger.ZERO) return ECPoint.POINT_INFINITY
        val threeX2PlusA = BigInteger.valueOf(3).multiply(pt.affineX.multiply(pt.affineX)).add(a).mod(p)
        val twoY = BigInteger.valueOf(2).multiply(pt.affineY).mod(p)
        val lambda = threeX2PlusA.multiply(twoY.modInverse(p)).mod(p)
        val rx = lambda.multiply(lambda).subtract(BigInteger.valueOf(2).multiply(pt.affineX)).mod(p)
        val ry = lambda.multiply(pt.affineX.subtract(rx)).subtract(pt.affineY).mod(p)
        return ECPoint(rx, ry)
    }
}
