package com.example.data

import android.content.Context
import com.example.R
import com.example.crypto.ShanSessionCrypto
import com.example.model.CallDoc
import com.example.model.ChannelDoc
import com.example.model.EncryptedMessageDoc
import com.example.model.IceCandidateDoc
import com.example.model.MediaChunkDoc
import com.example.model.PeerPresenceStatus
import com.example.model.PresenceDoc
import com.example.model.ShanCallStatus
import com.example.model.ShanCallType
import com.example.model.ShanMessageType
import com.example.model.ShanSlot
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

class ShanRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    companion object {
        const val CHANNEL_ID = "shan_20ss504_20bs230"
        const val MESSAGE_TTL_MILLIS = 24L * 60L * 60L * 1000L
        private const val CHUNK_RAW_SIZE_BYTES = 350 * 1024 // ~470KB Base64, safely < 750KB rule & 1MB doc limit
        private const val PROTOCOL_TAG = "ECDH-P256+HKDF+AES256GCM"
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google oturumu bulunamadı.")
    }

    private fun channelRef() = db.collection("channels").document(CHANNEL_ID)

    suspend fun ensureChannelAndPresence(
        slot: ShanSlot,
        myPublicKeyBase64: String
    ): Result<List<String>> {
        val channelPath = "channels/$CHANNEL_ID"
        return try {
            val uid = requireUserId()
            val channelSnap = channelRef().get().await()
            val memberUids: List<String>

            if (!channelSnap.exists()) {
                memberUids = listOf(uid)
                val keyField = if (slot == ShanSlot.USER_1) "slot1PublicKey" else "slot2PublicKey"
                val payload = mapOf(
                    "ownerUid" to uid,
                    "memberUids" to memberUids,
                    keyField to myPublicKeyBase64,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                channelRef().set(payload).await()
            } else {
                val channel = channelSnap.toObject(ChannelDoc::class.java)
                val existingMembers = channel?.memberUids ?: emptyList()
                val keyField = if (slot == ShanSlot.USER_1) "slot1PublicKey" else "slot2PublicKey"
                memberUids = when {
                    uid in existingMembers -> {
                        channelRef().update(
                            mapOf(
                                keyField to myPublicKeyBase64,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                        existingMembers
                    }
                    existingMembers.size < 2 -> {
                        val updated = existingMembers + uid
                        channelRef().update(
                            mapOf(
                                "memberUids" to updated,
                                keyField to myPublicKeyBase64,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                        updated
                    }
                    else -> {
                        throw IllegalStateException("Bu özel kanal yalnızca 2 kişi ile sınırlandırılmıştır.")
                    }
                }
            }

            // Update own presence document (never storing private nick in Firestore)
            val presenceRef = channelRef().collection("presence").document(slot.slotId)
            val presenceSnap = presenceRef.get().await()
            val nowMillis = System.currentTimeMillis()
            if (!presenceSnap.exists()) {
                presenceRef.set(
                    mapOf(
                        "slotId" to slot.slotId,
                        "authUid" to uid,
                        "memberUids" to memberUids,
                        "online" to true,
                        "publicKey" to myPublicKeyBase64,
                        "lastActiveMillis" to nowMillis,
                        "typing" to false,
                        "typingAtMillis" to 0L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                presenceRef.update(
                    mapOf(
                        "authUid" to uid,
                        "memberUids" to memberUids,
                        "online" to true,
                        "publicKey" to myPublicKeyBase64,
                        "lastActiveMillis" to nowMillis,
                        "typing" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            Result.success(memberUids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, channelPath)
            Result.failure(e)
        }
    }

    suspend fun updatePresenceHeartbeat(
        slot: ShanSlot,
        online: Boolean,
        memberUids: List<String>,
        myPublicKeyBase64: String
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/presence/${slot.slotId}"
        return try {
            val uid = requireUserId()
            val effectiveMembers = if (uid in memberUids) memberUids else (memberUids + uid).distinct().take(2)
            val updates = mutableMapOf<String, Any>(
                "authUid" to uid,
                "memberUids" to effectiveMembers,
                "online" to online,
                "publicKey" to myPublicKeyBase64,
                "lastActiveMillis" to System.currentTimeMillis(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!online) {
                updates["typing"] = false
            }
            channelRef().collection("presence").document(slot.slotId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateTypingStatus(
        slot: ShanSlot,
        isTyping: Boolean
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/presence/${slot.slotId}"
        return try {
            requireUserId()
            val now = System.currentTimeMillis()
            channelRef().collection("presence").document(slot.slotId).update(
                mapOf(
                    "typing" to isTyping,
                    "typingAtMillis" to if (isTyping) now else 0L,
                    "lastActiveMillis" to now,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observePeerPresence(activeSlot: ShanSlot): Flow<PeerPresenceStatus?> = flow {
        val uid = requireUserId()
        val peerSlot = activeSlot.peerSlot()
        val path = "channels/$CHANNEL_ID/presence"
        emitAll(
            channelRef().collection("presence")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    val docs = snapshot.toObjects(
                        PresenceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    val peerDoc = docs.firstOrNull { it.slotId == peerSlot.slotId }
                    peerDoc?.let {
                        val now = System.currentTimeMillis()
                        val isFresh = it.online && (now - it.lastActiveMillis) < 45_000L
                        val isTypingFresh = it.typing && isFresh && (now - it.typingAtMillis) < 8_000L
                        PeerPresenceStatus(
                            peerSlot = peerSlot,
                            isOnline = isFresh,
                            isTyping = isTypingFresh,
                            lastActiveMillis = it.lastActiveMillis,
                            peerPublicKeyBase64 = it.publicKey,
                            peerFingerprint = com.example.crypto.ShanCryptoEngine.computeFingerprintFromBase64(it.publicKey)
                        )
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeMessages(): Flow<List<EncryptedMessageDoc>> = flow {
        val uid = requireUserId()
        val path = "channels/$CHANNEL_ID/messages"
        emitAll(
            channelRef().collection("messages")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        EncryptedMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getChannelMemberUids(): List<String> {
        val uid = requireUserId()
        val snap = channelRef().get().await()
        val channel = snap.toObject(ChannelDoc::class.java)
        val members = channel?.memberUids ?: listOf(uid)
        return if (uid in members) members else listOf(uid)
    }

    suspend fun sendTextMessage(
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?,
        plaintext: String,
        replyToId: String?,
        replySenderSlot: ShanSlot?,
        replyPlainPreview: String?
    ): Result<Unit> {
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val path = "channels/$CHANNEL_ID/messages/$messageId"
        return try {
            val uid = requireUserId()
            val members = getChannelMemberUids()
            val encrypted = crypto.encryptText(plaintext, peerPublicKeyBase64)
            val replyEnc = if (!replyToId.isNullOrBlank() && !replyPlainPreview.isNullOrBlank()) {
                crypto.encryptText(replyPlainPreview.take(180), peerPublicKeyBase64)
            } else null

            val payload = mutableMapOf<String, Any?>(
                "senderSlot" to crypto.activeSlot.slotId,
                "senderUid" to uid,
                "memberUids" to members,
                "msgType" to ShanMessageType.TEXT.name,
                "ciphertext" to encrypted.ciphertextBase64,
                "iv" to encrypted.ivBase64,
                "ephemeralKey" to encrypted.saltBase64,
                "mediaChunkCount" to 0L,
                "edited" to false,
                "expiresAtMillis" to (System.currentTimeMillis() + MESSAGE_TTL_MILLIS),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (replyEnc != null && replyToId != null && replySenderSlot != null) {
                payload["replyToId"] = replyToId
                payload["replySenderSlot"] = replySenderSlot.slotId
                payload["replyCiphertext"] = "${replyEnc.saltBase64}:${replyEnc.ciphertextBase64}"
                payload["replyIv"] = replyEnc.ivBase64
            }

            @Suppress("UNCHECKED_CAST")
            val cleanPayload = payload.filterValues { it != null } as Map<String, Any>
            channelRef().collection("messages").document(messageId).set(cleanPayload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendMediaMessage(
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?,
        msgType: ShanMessageType,
        fileName: String,
        mimeType: String,
        plainBytes: ByteArray,
        caption: String,
        replyToId: String?,
        replySenderSlot: ShanSlot?,
        replyPlainPreview: String?
    ): Result<Unit> {
        val messageId = "media_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val path = "channels/$CHANNEL_ID/messages/$messageId"
        return try {
            val uid = requireUserId()
            val members = getChannelMemberUids()
            require(plainBytes.size <= 14 * 1024 * 1024) {
                "Dosya boyutu bellek içi aktarım sınırı olan 14 MB'ı aşamaz."
            }

            val metaJson = JSONObject().apply {
                put("fileName", fileName)
                put("caption", caption)
                put("protocol", PROTOCOL_TAG)
            }.toString()
            val encryptedMeta = crypto.encryptText(metaJson, peerPublicKeyBase64)

            val totalChunks = ((plainBytes.size + CHUNK_RAW_SIZE_BYTES - 1) / CHUNK_RAW_SIZE_BYTES).coerceAtLeast(1)
            for (idx in 0 until totalChunks) {
                val start = idx * CHUNK_RAW_SIZE_BYTES
                val end = minOf(start + CHUNK_RAW_SIZE_BYTES, plainBytes.size)
                val slice = plainBytes.copyOfRange(start, end)
                val encChunk = crypto.encryptBytes(slice, peerPublicKeyBase64)
                val chunkPayload = mapOf(
                    "senderUid" to uid,
                    "memberUids" to members,
                    "chunkIndex" to idx.toLong(),
                    "dataCiphertext" to encChunk.ciphertextBase64,
                    "dataIv" to "${encChunk.saltBase64}:${encChunk.ivBase64}",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                channelRef().collection("messages").document(messageId)
                    .collection("chunks").document("chunk_$idx")
                    .set(chunkPayload).await()
            }

            val replyEnc = if (!replyToId.isNullOrBlank() && !replyPlainPreview.isNullOrBlank()) {
                crypto.encryptText(replyPlainPreview.take(180), peerPublicKeyBase64)
            } else null

            val payload = mutableMapOf<String, Any?>(
                "senderSlot" to crypto.activeSlot.slotId,
                "senderUid" to uid,
                "memberUids" to members,
                "msgType" to msgType.name,
                "ciphertext" to encryptedMeta.ciphertextBase64,
                "iv" to encryptedMeta.ivBase64,
                "ephemeralKey" to encryptedMeta.saltBase64,
                "mediaChunkCount" to totalChunks.toLong(),
                "mediaMimeType" to mimeType.take(120),
                "mediaSizeBytes" to plainBytes.size.toLong(),
                "edited" to false,
                "expiresAtMillis" to (System.currentTimeMillis() + MESSAGE_TTL_MILLIS),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (replyEnc != null && replyToId != null && replySenderSlot != null) {
                payload["replyToId"] = replyToId
                payload["replySenderSlot"] = replySenderSlot.slotId
                payload["replyCiphertext"] = "${replyEnc.saltBase64}:${replyEnc.ciphertextBase64}"
                payload["replyIv"] = replyEnc.ivBase64
            }

            @Suppress("UNCHECKED_CAST")
            val cleanPayload = payload.filterValues { it != null } as Map<String, Any>
            channelRef().collection("messages").document(messageId).set(cleanPayload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun loadDecryptedMediaInMemory(
        messageId: String,
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?
    ): Result<ByteArray> {
        val path = "channels/$CHANNEL_ID/messages/$messageId/chunks"
        return try {
            val uid = requireUserId()
            val snap = channelRef().collection("messages").document(messageId)
                .collection("chunks")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            val chunks = snap.toObjects(MediaChunkDoc::class.java).sortedBy { it.chunkIndex }
            val output = java.io.ByteArrayOutputStream()
            for (chunk in chunks) {
                val parts = chunk.dataIv.split(":")
                if (parts.size != 2) continue
                val saltB64 = parts[0]
                val ivB64 = parts[1]
                val plainSlice = crypto.decryptBytes(
                    ciphertextBase64 = chunk.dataCiphertext,
                    ivBase64 = ivB64,
                    saltBase64 = saltB64,
                    peerPublicKeyBase64 = peerPublicKeyBase64
                )
                output.write(plainSlice)
            }
            Result.success(output.toByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun editMessage(
        messageId: String,
        newPlaintext: String,
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/messages/$messageId"
        return try {
            requireUserId()
            require(messageId.isNotBlank())
            val encrypted = crypto.encryptText(newPlaintext, peerPublicKeyBase64)
            channelRef().collection("messages").document(messageId).update(
                mapOf(
                    "ciphertext" to encrypted.ciphertextBase64,
                    "iv" to encrypted.ivBase64,
                    "ephemeralKey" to encrypted.saltBase64,
                    "edited" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleReaction(
        messageId: String,
        activeSlot: ShanSlot,
        currentReactions: Map<String, String>,
        emoji: String
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/messages/$messageId"
        return try {
            requireUserId()
            val updated = currentReactions.toMutableMap()
            if (updated[activeSlot.slotId] == emoji) {
                updated.remove(activeSlot.slotId)
            } else {
                updated[activeSlot.slotId] = emoji
            }
            channelRef().collection("messages").document(messageId).update(
                mapOf(
                    "reactions" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageAndChunks(messageId: String): Result<Unit> {
        val path = "channels/$CHANNEL_ID/messages/$messageId"
        return try {
            val uid = requireUserId()
            val chunksSnap = channelRef().collection("messages").document(messageId)
                .collection("chunks")
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
            for (doc in chunksSnap.documents) {
                doc.reference.delete().await()
            }
            channelRef().collection("messages").document(messageId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Encrypted WebRTC Call Signaling ---

    fun observeCalls(): Flow<List<CallDoc>> = flow {
        val uid = requireUserId()
        val path = "channels/$CHANNEL_ID/calls"
        emitAll(
            channelRef().collection("calls")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createCallOffer(
        callId: String,
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?,
        callType: ShanCallType,
        offerSdp: String
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/calls/$callId"
        return try {
            val uid = requireUserId()
            val members = getChannelMemberUids()
            val encOffer = crypto.encryptText(offerSdp, peerPublicKeyBase64)
            channelRef().collection("calls").document(callId).set(
                mapOf(
                    "callerSlot" to crypto.activeSlot.slotId,
                    "callerUid" to uid,
                    "calleeSlot" to crypto.activeSlot.peerSlot().slotId,
                    "memberUids" to members,
                    "callType" to callType.name,
                    "status" to ShanCallStatus.RINGING.name,
                    "offerCiphertext" to encOffer.ciphertextBase64,
                    "offerIv" to "${encOffer.saltBase64}:${encOffer.ivBase64}",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun answerCall(
        callId: String,
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?,
        answerSdp: String
    ): Result<Unit> {
        val path = "channels/$CHANNEL_ID/calls/$callId"
        return try {
            requireUserId()
            val encAnswer = crypto.encryptText(answerSdp, peerPublicKeyBase64)
            channelRef().collection("calls").document(callId).update(
                mapOf(
                    "status" to ShanCallStatus.ACTIVE.name,
                    "answerCiphertext" to encAnswer.ciphertextBase64,
                    "answerIv" to "${encAnswer.saltBase64}:${encAnswer.ivBase64}",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateCallStatus(callId: String, status: ShanCallStatus): Result<Unit> {
        val path = "channels/$CHANNEL_ID/calls/$callId"
        return try {
            requireUserId()
            channelRef().collection("calls").document(callId).update(
                mapOf(
                    "status" to status.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendIceCandidate(
        callId: String,
        crypto: ShanSessionCrypto,
        peerPublicKeyBase64: String?,
        candidateJson: String
    ): Result<Unit> {
        val candId = "ice_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val path = "channels/$CHANNEL_ID/calls/$callId/candidates/$candId"
        return try {
            val uid = requireUserId()
            val members = getChannelMemberUids()
            val encCand = crypto.encryptText(candidateJson, peerPublicKeyBase64)
            channelRef().collection("calls").document(callId)
                .collection("candidates").document(candId)
                .set(
                    mapOf(
                        "senderUid" to uid,
                        "senderSlot" to crypto.activeSlot.slotId,
                        "memberUids" to members,
                        "candidateCiphertext" to encCand.ciphertextBase64,
                        "candidateIv" to "${encCand.saltBase64}:${encCand.ivBase64}",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    fun observeIceCandidates(callId: String): Flow<List<IceCandidateDoc>> = flow {
        val uid = requireUserId()
        val path = "channels/$CHANNEL_ID/calls/$callId/candidates"
        emitAll(
            channelRef().collection("calls").document(callId)
                .collection("candidates")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        IceCandidateDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }
}
