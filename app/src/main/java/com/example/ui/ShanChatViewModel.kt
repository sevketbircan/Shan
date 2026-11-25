package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.ShanWebRtcManager
import com.example.crypto.ShanCryptoEngine
import com.example.crypto.ShanSessionCrypto
import com.example.data.ShanRepository
import com.example.model.ActiveCallSession
import com.example.model.CallDoc
import com.example.model.DecryptedMessage
import com.example.model.EncryptedMessageDoc
import com.example.model.PeerPresenceStatus
import com.example.model.ShanCallStatus
import com.example.model.ShanCallType
import com.example.model.ShanMessageType
import com.example.model.ShanSlot
import com.example.model.UiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.ByteArrayOutputStream
import java.util.UUID

class ShanChatViewModel(
    private val repository: ShanRepository,
    private val crypto: ShanSessionCrypto,
    private val currentAuthUid: String
) : ViewModel() {

    private val _memberUids = MutableStateFlow(listOf(currentAuthUid))
    private val _peerPublicKeyB64 = MutableStateFlow<String?>(null)
    private val _replyTarget = MutableStateFlow<DecryptedMessage?>(null)
    val replyTarget: StateFlow<DecryptedMessage?> = _replyTarget.asStateFlow()

    private val _editTarget = MutableStateFlow<DecryptedMessage?>(null)
    val editTarget: StateFlow<DecryptedMessage?> = _editTarget.asStateFlow()

    private val _mediaBytesRamCache = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val mediaBytesRamCache: StateFlow<Map<String, ByteArray>> = _mediaBytesRamCache.asStateFlow()

    private val _loadingMediaIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingMediaIds: StateFlow<Set<String>> = _loadingMediaIds.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _newRemoteMessageEvent = MutableStateFlow(0L)
    val newRemoteMessageEvent: StateFlow<Long> = _newRemoteMessageEvent.asStateFlow()

    private val seenMessageIds = mutableSetOf<String>()
    private var initialSnapshotLoaded = false
    private val purgedIds = mutableSetOf<String>()

    // Call State
    private val _activeCall = MutableStateFlow<ActiveCallSession?>(null)
    val activeCall: StateFlow<ActiveCallSession?> = _activeCall.asStateFlow()

    private val _incomingCall = MutableStateFlow<ActiveCallSession?>(null)
    val incomingCall: StateFlow<ActiveCallSession?> = _incomingCall.asStateFlow()

    private val _callIceState = MutableStateFlow<PeerConnection.IceConnectionState?>(null)
    val callIceState: StateFlow<PeerConnection.IceConnectionState?> = _callIceState.asStateFlow()

    private var webRtcManager: ShanWebRtcManager? = null
    private var iceObserverJob: Job? = null
    private val processedIceIds = mutableSetOf<String>()
    private var heartbeatJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var lastTypingSentMillis = 0L
    private var isCurrentlyTyping = false

    val peerPresence: StateFlow<PeerPresenceStatus?> = repository
        .observePeerPresence(crypto.activeSlot)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing presence", firestoreError ?: error)
            emit(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val messagesState: StateFlow<UiState<List<DecryptedMessage>>> = combine(
        repository.observeMessages(),
        peerPresence
    ) { docs, presence ->
        val peerPub = presence?.peerPublicKeyBase64?.takeIf { it.isNotBlank() }
        if (peerPub != null) {
            _peerPublicKeyB64.value = peerPub
        }
        val now = System.currentTimeMillis()
        val expiredDocs = docs.filter { doc ->
            val createdMs = doc.createdAt?.toDate()?.time ?: now
            (now - createdMs) >= ShanRepository.MESSAGE_TTL_MILLIS ||
                (doc.expiresAtMillis > 0L && now >= doc.expiresAtMillis)
        }
        if (expiredDocs.isNotEmpty()) {
            schedulePurgeExpired(expiredDocs.map { it.id })
        }

        val activeDocs = docs.filter { doc ->
            val createdMs = doc.createdAt?.toDate()?.time ?: now
            (now - createdMs) < ShanRepository.MESSAGE_TTL_MILLIS &&
                (doc.expiresAtMillis <= 0L || now < doc.expiresAtMillis)
        }

        val decrypted = activeDocs.map { doc -> decryptDoc(doc, peerPub) }

        if (!initialSnapshotLoaded) {
            seenMessageIds.addAll(decrypted.map { it.id })
            initialSnapshotLoaded = true
        } else {
            val newRemote = decrypted.any { !it.isMine && it.id !in seenMessageIds }
            seenMessageIds.addAll(decrypted.map { it.id })
            if (newRemote) {
                _newRemoteMessageEvent.value = System.currentTimeMillis()
            }
        }

        UiState.Success(decrypted) as UiState<List<DecryptedMessage>>
    }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing messages", rootError)
            emit(UiState.Error(rootError.localizedMessage ?: "Şifreli mesajlar yüklenemedi"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    init {
        initializeChannel()
        observeCallSignaling()
    }

    private fun initializeChannel() {
        viewModelScope.launch {
            val res = repository.ensureChannelAndPresence(
                slot = crypto.activeSlot,
                myPublicKeyBase64 = crypto.myPublicKeyBase64
            )
            res.onSuccess { members ->
                _memberUids.value = members
                startHeartbeatLoop()
            }.onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Kanal başlatılamadı"
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(20_000L)
                repository.updatePresenceHeartbeat(
                    slot = crypto.activeSlot,
                    online = true,
                    memberUids = _memberUids.value,
                    myPublicKeyBase64 = crypto.myPublicKeyBase64
                )
            }
        }
    }

    fun markOfflineOnLock() {
        heartbeatJob?.cancel()
        typingTimeoutJob?.cancel()
        isCurrentlyTyping = false
        viewModelScope.launch {
            repository.updatePresenceHeartbeat(
                slot = crypto.activeSlot,
                online = false,
                memberUids = _memberUids.value,
                myPublicKeyBase64 = crypto.myPublicKeyBase64
            )
        }
        releaseCallResources()
        // Wipe RAM media cache immediately on lock
        _mediaBytesRamCache.value = emptyMap()
    }

    fun onComposerTextChanged(rawText: String) {
        if (rawText.isBlank()) {
            clearTypingState()
            return
        }
        val now = System.currentTimeMillis()
        if (!isCurrentlyTyping || (now - lastTypingSentMillis) >= 2_500L) {
            isCurrentlyTyping = true
            lastTypingSentMillis = now
            viewModelScope.launch {
                repository.updateTypingStatus(crypto.activeSlot, isTyping = true)
            }
        }
        typingTimeoutJob?.cancel()
        typingTimeoutJob = viewModelScope.launch {
            delay(4_000L)
            clearTypingState()
        }
    }

    private fun clearTypingState() {
        typingTimeoutJob?.cancel()
        typingTimeoutJob = null
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            lastTypingSentMillis = 0L
            viewModelScope.launch {
                repository.updateTypingStatus(crypto.activeSlot, isTyping = false)
            }
        }
    }

    private fun schedulePurgeExpired(ids: List<String>) {
        val toPurge = ids.filter { it.isNotBlank() && it !in purgedIds }
        if (toPurge.isEmpty()) return
        purgedIds.addAll(toPurge)
        viewModelScope.launch {
            for (id in toPurge) {
                repository.deleteMessageAndChunks(id)
            }
        }
    }

    private fun decryptDoc(doc: EncryptedMessageDoc, peerPubB64: String?): DecryptedMessage {
        val senderSlot = ShanSlot.fromSlotId(doc.senderSlot) ?: crypto.activeSlot.peerSlot()
        val msgType = ShanMessageType.safeParse(doc.msgType)
        val createdMs = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        val expiresMs = if (doc.expiresAtMillis > 0L) doc.expiresAtMillis else createdMs + ShanRepository.MESSAGE_TTL_MILLIS

        var text = ""
        var fileName: String? = null
        var failed = false

        try {
            val plain = crypto.decryptText(
                ciphertextBase64 = doc.ciphertext,
                ivBase64 = doc.iv,
                saltBase64 = doc.ephemeralKey,
                peerPublicKeyBase64 = peerPubB64
            )
            if (msgType == ShanMessageType.TEXT) {
                text = plain
            } else {
                val obj = JSONObject(plain)
                fileName = obj.optString("fileName", "Dosya")
                text = obj.optString("caption", "")
            }
        } catch (_: Exception) {
            failed = true
            text = "Şifre çözme anahtarı eşleşmedi"
        }

        var replyPreview: String? = null
        if (!doc.replyCiphertext.isNullOrBlank() && !doc.replyIv.isNullOrBlank()) {
            try {
                val parts = doc.replyCiphertext.split(":", limit = 2)
                if (parts.size == 2) {
                    replyPreview = crypto.decryptText(
                        ciphertextBase64 = parts[1],
                        ivBase64 = doc.replyIv,
                        saltBase64 = parts[0],
                        peerPublicKeyBase64 = peerPubB64
                    )
                }
            } catch (_: Exception) {
                replyPreview = "Şifreli alıntı"
            }
        }

        return DecryptedMessage(
            id = doc.id,
            senderSlot = senderSlot,
            isMine = senderSlot == crypto.activeSlot,
            msgType = msgType,
            text = text,
            replyToId = doc.replyToId,
            replySenderSlot = ShanSlot.fromSlotId(doc.replySenderSlot),
            replyPreview = replyPreview,
            mediaChunkCount = doc.mediaChunkCount.toInt(),
            mediaMimeType = doc.mediaMimeType,
            mediaFileName = fileName,
            mediaSizeBytes = doc.mediaSizeBytes ?: 0L,
            edited = doc.edited,
            reactions = doc.reactions,
            createdAtMillis = createdMs,
            expiresAtMillis = expiresMs,
            decryptionFailed = failed
        )
    }

    fun setReplyTarget(msg: DecryptedMessage?) {
        _editTarget.value = null
        _replyTarget.value = msg
    }

    fun setEditTarget(msg: DecryptedMessage?) {
        _replyTarget.value = null
        _editTarget.value = msg
    }

    fun clearError() {
        _actionError.value = null
    }

    fun sendOrEditText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _isSending.value) return
        clearTypingState()
        val editing = _editTarget.value
        val replying = _replyTarget.value

        viewModelScope.launch {
            _isSending.value = true
            val result = if (editing != null) {
                repository.editMessage(
                    messageId = editing.id,
                    newPlaintext = text,
                    crypto = crypto,
                    peerPublicKeyBase64 = _peerPublicKeyB64.value
                )
            } else {
                val replySnippet = replying?.let {
                    if (it.msgType == ShanMessageType.TEXT) it.text else (it.mediaFileName ?: "Medya")
                }
                repository.sendTextMessage(
                    crypto = crypto,
                    peerPublicKeyBase64 = _peerPublicKeyB64.value,
                    plaintext = text,
                    replyToId = replying?.id,
                    replySenderSlot = replying?.senderSlot,
                    replyPlainPreview = replySnippet
                )
            }
            _isSending.value = false
            result.onSuccess {
                _editTarget.value = null
                _replyTarget.value = null
            }.onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Mesaj gönderilemedi"
            }
        }
    }

    fun sendPickedMedia(context: Context, uri: Uri, caption: String, isDocument: Boolean) {
        if (_isSending.value) return
        val replying = _replyTarget.value
        viewModelScope.launch {
            _isSending.value = true
            try {
                val resolver = context.applicationContext.contentResolver
                val rawMime = resolver.getType(uri) ?: if (isDocument) "application/octet-stream" else "image/jpeg"
                val fileName = queryFileName(context, uri) ?: when {
                    rawMime.startsWith("image/") -> "photo_${System.currentTimeMillis()}.jpg"
                    rawMime.startsWith("video/") -> "video_${System.currentTimeMillis()}.mp4"
                    else -> "dosya_${System.currentTimeMillis()}"
                }

                val rawBytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Dosya okunamadı")
                }

                val msgType = when {
                    rawMime.startsWith("image/") -> ShanMessageType.IMAGE
                    rawMime.startsWith("video/") -> ShanMessageType.VIDEO
                    else -> ShanMessageType.FILE
                }

                // Optimize ultra-high-res images smoothly in RAM while preserving high quality (up to 1920px, JPEG 90)
                val finalBytes: ByteArray
                val finalMime: String
                if (msgType == ShanMessageType.IMAGE && rawBytes.size > 1_800_000) {
                    finalBytes = withContext(Dispatchers.Default) { compressImageInMemory(rawBytes) }
                    finalMime = "image/jpeg"
                } else {
                    finalBytes = rawBytes
                    finalMime = rawMime
                }

                val replySnippet = replying?.let {
                    if (it.msgType == ShanMessageType.TEXT) it.text else (it.mediaFileName ?: "Medya")
                }

                val result = repository.sendMediaMessage(
                    crypto = crypto,
                    peerPublicKeyBase64 = _peerPublicKeyB64.value,
                    msgType = msgType,
                    fileName = fileName,
                    mimeType = finalMime,
                    plainBytes = finalBytes,
                    caption = caption.trim(),
                    replyToId = replying?.id,
                    replySenderSlot = replying?.senderSlot,
                    replyPlainPreview = replySnippet
                )
                result.onSuccess {
                    _replyTarget.value = null
                }.onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Medya gönderilemedi"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionError.value = e.localizedMessage ?: "Medya işlenemedi"
            } finally {
                _isSending.value = false
            }
        }
    }

    fun loadMediaIntoRam(messageId: String) {
        if (_mediaBytesRamCache.value.containsKey(messageId) || messageId in _loadingMediaIds.value) return
        viewModelScope.launch {
            _loadingMediaIds.value = _loadingMediaIds.value + messageId
            val result = repository.loadDecryptedMediaInMemory(
                messageId = messageId,
                crypto = crypto,
                peerPublicKeyBase64 = _peerPublicKeyB64.value
            )
            result.onSuccess { bytes ->
                _mediaBytesRamCache.value = _mediaBytesRamCache.value + (messageId to bytes)
            }.onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Medya şifresi çözülemedi"
            }
            _loadingMediaIds.value = _loadingMediaIds.value - messageId
        }
    }

    fun toggleReaction(message: DecryptedMessage, emoji: String) {
        viewModelScope.launch {
            repository.toggleReaction(
                messageId = message.id,
                activeSlot = crypto.activeSlot,
                currentReactions = message.reactions,
                emoji = emoji
            ).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Tepki güncellenemedi"
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (_replyTarget.value?.id == messageId) _replyTarget.value = null
        if (_editTarget.value?.id == messageId) _editTarget.value = null
        _mediaBytesRamCache.value = _mediaBytesRamCache.value - messageId
        viewModelScope.launch {
            repository.deleteMessageAndChunks(messageId).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Mesaj silinemedi"
            }
        }
    }

    // --- Voice & Video Calling (Encrypted WebRTC Signaling) ---

    private fun observeCallSignaling() {
        viewModelScope.launch {
            repository.observeCalls()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                }
                .collect { calls ->
                    handleCallsSnapshot(calls)
                }
        }
    }

    private fun handleCallsSnapshot(calls: List<CallDoc>) {
        val now = System.currentTimeMillis()
        val recentCalls = calls.filter { call ->
            val createdMs = call.createdAt?.toDate()?.time ?: now
            (now - createdMs) < 15 * 60 * 1000L
        }

        val currentActive = _activeCall.value
        if (currentActive != null) {
            val matching = recentCalls.firstOrNull { it.id == currentActive.callId }
            if (matching != null) {
                val status = ShanCallStatus.safeParse(matching.status)
                if (status == ShanCallStatus.ENDED || status == ShanCallStatus.REJECTED) {
                    releaseCallResources()
                    return
                }
                if (status == ShanCallStatus.ACTIVE &&
                    !matching.answerCiphertext.isNullOrBlank() &&
                    !matching.answerIv.isNullOrBlank() &&
                    currentActive.answerSdp == null
                ) {
                    val answerSdp = decryptCallSdp(matching.answerCiphertext, matching.answerIv)
                    if (answerSdp != null) {
                        _activeCall.value = currentActive.copy(status = status, answerSdp = answerSdp)
                        webRtcManager?.applyRemoteAnswer(answerSdp)
                    }
                }
            }
        }

        // Check incoming RINGING calls addressed to me
        val ringing = recentCalls.firstOrNull { call ->
            ShanCallStatus.safeParse(call.status) == ShanCallStatus.RINGING &&
                call.calleeSlot == crypto.activeSlot.slotId &&
                call.id != currentActive?.callId
        }
        if (ringing != null) {
            val offerSdp = decryptCallSdp(ringing.offerCiphertext, ringing.offerIv) ?: ""
            _incomingCall.value = ActiveCallSession(
                callId = ringing.id,
                callType = ShanCallType.safeParse(ringing.callType),
                status = ShanCallStatus.RINGING,
                isIncoming = true,
                callerSlot = ShanSlot.fromSlotId(ringing.callerSlot) ?: crypto.activeSlot.peerSlot(),
                calleeSlot = crypto.activeSlot,
                offerSdp = offerSdp
            )
        } else {
            _incomingCall.value = null
        }
    }

    private fun decryptCallSdp(ciphertext: String, combinedIv: String): String? {
        return try {
            val parts = combinedIv.split(":", limit = 2)
            if (parts.size != 2) return null
            crypto.decryptText(
                ciphertextBase64 = ciphertext,
                ivBase64 = parts[1],
                saltBase64 = parts[0],
                peerPublicKeyBase64 = _peerPublicKeyB64.value
            )
        } catch (_: Exception) {
            null
        }
    }

    fun startOutgoingCall(context: Context, callType: ShanCallType): ShanWebRtcManager {
        releaseCallResources()
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        val manager = createWebRtcManager(context, callId)
        webRtcManager = manager
        manager.startSession(isVideo = callType == ShanCallType.VIDEO)

        _activeCall.value = ActiveCallSession(
            callId = callId,
            callType = callType,
            status = ShanCallStatus.RINGING,
            isIncoming = false,
            callerSlot = crypto.activeSlot,
            calleeSlot = crypto.activeSlot.peerSlot(),
            offerSdp = ""
        )

        manager.createOffer { offerSdp ->
            viewModelScope.launch {
                repository.createCallOffer(
                    callId = callId,
                    crypto = crypto,
                    peerPublicKeyBase64 = _peerPublicKeyB64.value,
                    callType = callType,
                    offerSdp = offerSdp
                ).onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Arama başlatılamadı"
                    releaseCallResources()
                }
            }
        }
        startObservingIce(callId)
        return manager
    }

    fun acceptIncomingCall(context: Context): ShanWebRtcManager? {
        val incoming = _incomingCall.value ?: return null
        releaseCallResources()
        val manager = createWebRtcManager(context, incoming.callId)
        webRtcManager = manager
        manager.startSession(isVideo = incoming.callType == ShanCallType.VIDEO)

        _incomingCall.value = null
        _activeCall.value = incoming.copy(status = ShanCallStatus.ACTIVE)

        manager.acceptOfferAndCreateAnswer(incoming.offerSdp) { answerSdp ->
            viewModelScope.launch {
                repository.answerCall(
                    callId = incoming.callId,
                    crypto = crypto,
                    peerPublicKeyBase64 = _peerPublicKeyB64.value,
                    answerSdp = answerSdp
                )
            }
        }
        startObservingIce(incoming.callId)
        return manager
    }

    fun rejectIncomingCall() {
        val incoming = _incomingCall.value ?: return
        _incomingCall.value = null
        viewModelScope.launch {
            repository.updateCallStatus(incoming.callId, ShanCallStatus.REJECTED)
        }
    }

    fun endActiveCall() {
        val active = _activeCall.value
        releaseCallResources()
        if (active != null) {
            viewModelScope.launch {
                repository.updateCallStatus(active.callId, ShanCallStatus.ENDED)
            }
        }
    }

    fun getActiveWebRtcManager(): ShanWebRtcManager? = webRtcManager

    private fun createWebRtcManager(context: Context, callId: String): ShanWebRtcManager {
        return ShanWebRtcManager(
            context = context.applicationContext,
            onLocalIceCandidate = { candJson ->
                viewModelScope.launch {
                    repository.sendIceCandidate(
                        callId = callId,
                        crypto = crypto,
                        peerPublicKeyBase64 = _peerPublicKeyB64.value,
                        candidateJson = candJson
                    )
                }
            },
            onConnectionStateChanged = { state ->
                _callIceState.value = state
            }
        )
    }

    private fun startObservingIce(callId: String) {
        iceObserverJob?.cancel()
        processedIceIds.clear()
        iceObserverJob = viewModelScope.launch {
            repository.observeIceCandidates(callId)
                .catch { /* handled cleanly */ }
                .collect { docs ->
                    for (doc in docs) {
                        if (doc.senderSlot == crypto.activeSlot.slotId || doc.id in processedIceIds) continue
                        processedIceIds.add(doc.id)
                        val candJson = decryptCallSdp(doc.candidateCiphertext, doc.candidateIv)
                        if (candJson != null) {
                            webRtcManager?.addRemoteIceCandidate(candJson)
                        }
                    }
                }
        }
    }

    private fun releaseCallResources() {
        iceObserverJob?.cancel()
        iceObserverJob = null
        processedIceIds.clear()
        webRtcManager?.release()
        webRtcManager = null
        _activeCall.value = null
        _callIceState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        releaseCallResources()
        _mediaBytesRamCache.value = emptyMap()
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun compressImageInMemory(raw: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        val maxDim = 1920
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts) ?: return raw
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "ShanChatVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
