package com.example.call

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class ShanWebRtcManager(
    private val context: Context,
    private val onLocalIceCandidate: (String) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit
) {
    companion object {
        private const val TAG = "ShanWebRTC"
        @Volatile
        private var initialized = false
    }

    val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    init {
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            initialized = true
        }
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startSession(isVideo: Boolean) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val json = JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("sdp", candidate.sdp)
                    }.toString()
                    onLocalIceCandidate(json)
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    onConnectionStateChanged(newState)
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("SHAN_AUDIO", audioSource).apply {
            setEnabled(true)
            peerConnection?.addTrack(this, listOf("SHAN_STREAM"))
        }

        if (isVideo) {
            setupVideoTrack()
        }
        setSpeakerphoneEnabled(isVideo)
    }

    private fun setupVideoTrack() {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (deviceName == null) {
            Log.w(TAG, "No camera device found")
            return
        }
        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("ShanCaptureThread", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource?.capturerObserver)
        try {
            capturer.startCapture(720, 1280, 30)
        } catch (e: Exception) {
            Log.w(TAG, "Camera startCapture error: ${e.message}")
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack("SHAN_VIDEO", videoSource).apply {
            setEnabled(true)
            localRenderer?.let { addSink(it) }
            peerConnection?.addTrack(this, listOf("SHAN_STREAM"))
        }
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localVideoTrack?.addSink(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    fun createOffer(onOfferReady: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalOffer"), sdp)
                onOfferReady(sdp.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer err: $err") }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun acceptOfferAndCreateAnswer(offerSdp: String, onAnswerReady: (String) -> Unit) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription) {
                        peerConnection?.setLocalDescription(simpleSdpObserver("setLocalAnswer"), answerSdp)
                        onAnswerReady(answerSdp.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) { Log.e(TAG, "createAnswer err: $err") }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) { Log.e(TAG, "setRemoteOffer err: $err") }
        }, remoteDesc)
    }

    fun applyRemoteAnswer(answerSdp: String) {
        if (remoteDescriptionSet) return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) { Log.e(TAG, "setRemoteAnswer err: $err") }
        }, remoteDesc)
    }

    fun addRemoteIceCandidate(candidateJson: String) {
        try {
            val obj = JSONObject(candidateJson)
            val candidate = IceCandidate(
                obj.getString("sdpMid"),
                obj.getInt("sdpMLineIndex"),
                obj.getString("sdp")
            )
            if (remoteDescriptionSet) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                pendingIceCandidates.add(candidate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid ICE JSON: ${e.message}")
        }
    }

    private fun flushPendingIce() {
        val iter = pendingIceCandidates.iterator()
        while (iter.hasNext()) {
            peerConnection?.addIceCandidate(iter.next())
            iter.remove()
        }
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphoneEnabled(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = enabled
    }

    fun release() {
        try {
            localRenderer?.let { localVideoTrack?.removeSink(it) }
            remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            eglBase.release()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC release warning: ${e.message}")
        }
    }

    private fun simpleSdpObserver(label: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) { Log.w(TAG, "$label create failure: $err") }
        override fun onSetFailure(err: String?) { Log.w(TAG, "$label set failure: $err") }
    }
}
