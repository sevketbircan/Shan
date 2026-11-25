package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class ShanSlot(
    val slotId: String,
    val privateNick: String
) {
    USER_1(slotId = "20ss504", privateNick = "Aslı"),
    USER_2(slotId = "20bs230", privateNick = "Şevket");

    fun peerSlot(): ShanSlot = when (this) {
        USER_1 -> USER_2
        USER_2 -> USER_1
    }

    companion object {
        val suggestedUsernames: List<String> = entries.map { it.slotId }

        fun fromSlotId(raw: String?): ShanSlot? =
            entries.firstOrNull { it.slotId.equals(raw?.trim(), ignoreCase = false) }
    }
}

enum class ShanMessageType {
    TEXT,
    IMAGE,
    VIDEO,
    FILE;

    companion object {
        fun safeParse(raw: String?): ShanMessageType =
            entries.firstOrNull { it.name == raw } ?: TEXT
    }
}

enum class ShanCallType {
    VOICE,
    VIDEO;

    companion object {
        fun safeParse(raw: String?): ShanCallType =
            entries.firstOrNull { it.name == raw } ?: VOICE
    }
}

enum class ShanCallStatus {
    RINGING,
    ACTIVE,
    ENDED,
    REJECTED;

    companion object {
        fun safeParse(raw: String?): ShanCallStatus =
            entries.firstOrNull { it.name == raw } ?: ENDED
    }
}

@Keep
@IgnoreExtraProperties
data class ChannelDoc(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val slot1PublicKey: String = "",
    val slot2PublicKey: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PresenceDoc(
    @DocumentId val id: String = "",
    val slotId: String = "",
    val authUid: String = "",
    val memberUids: List<String> = emptyList(),
    val online: Boolean = false,
    val publicKey: String = "",
    val lastActiveMillis: Long = 0L,
    val typing: Boolean = false,
    val typingAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class EncryptedMessageDoc(
    @DocumentId val id: String = "",
    val senderSlot: String = "",
    val senderUid: String = "",
    val memberUids: List<String> = emptyList(),
    val msgType: String = ShanMessageType.TEXT.name,
    val ciphertext: String = "",
    val iv: String = "",
    val ephemeralKey: String = "",
    val replyToId: String? = null,
    val replySenderSlot: String? = null,
    val replyCiphertext: String? = null,
    val replyIv: String? = null,
    val mediaChunkCount: Long = 0L,
    val mediaMimeType: String? = null,
    val mediaSizeBytes: Long? = null,
    val edited: Boolean = false,
    val reactions: Map<String, String> = emptyMap(),
    val expiresAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MediaChunkDoc(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val memberUids: List<String> = emptyList(),
    val chunkIndex: Long = 0L,
    val dataCiphertext: String = "",
    val dataIv: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallDoc(
    @DocumentId val id: String = "",
    val callerSlot: String = "",
    val callerUid: String = "",
    val calleeSlot: String = "",
    val memberUids: List<String> = emptyList(),
    val callType: String = ShanCallType.VOICE.name,
    val status: String = ShanCallStatus.RINGING.name,
    val offerCiphertext: String = "",
    val offerIv: String = "",
    val answerCiphertext: String? = null,
    val answerIv: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidateDoc(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val senderSlot: String = "",
    val memberUids: List<String> = emptyList(),
    val candidateCiphertext: String = "",
    val candidateIv: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class DecryptedMessage(
    val id: String,
    val senderSlot: ShanSlot,
    val isMine: Boolean,
    val msgType: ShanMessageType,
    val text: String,
    val replyToId: String? = null,
    val replySenderSlot: ShanSlot? = null,
    val replyPreview: String? = null,
    val mediaChunkCount: Int = 0,
    val mediaMimeType: String? = null,
    val mediaFileName: String? = null,
    val mediaSizeBytes: Long = 0L,
    val edited: Boolean = false,
    val reactions: Map<String, String> = emptyMap(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val decryptionFailed: Boolean = false
)

data class PeerPresenceStatus(
    val peerSlot: ShanSlot,
    val isOnline: Boolean,
    val isTyping: Boolean = false,
    val lastActiveMillis: Long,
    val peerPublicKeyBase64: String,
    val peerFingerprint: String
)

data class ActiveCallSession(
    val callId: String,
    val callType: ShanCallType,
    val status: ShanCallStatus,
    val isIncoming: Boolean,
    val callerSlot: ShanSlot,
    val calleeSlot: ShanSlot,
    val offerSdp: String,
    val answerSdp: String? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
