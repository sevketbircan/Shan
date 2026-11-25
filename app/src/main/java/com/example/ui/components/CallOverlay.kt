package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.call.ShanWebRtcManager
import com.example.model.ActiveCallSession
import com.example.model.ShanCallStatus
import com.example.model.ShanCallType
import com.example.ui.theme.ShanMonoFontFamily
import kotlinx.coroutines.delay
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun IncomingCallOverlay(
    incomingCall: ActiveCallSession,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val isVideo = incomingCall.callType == ShanCallType.VIDEO
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0060B11))
            .safeDrawingPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF00E676).copy(alpha = 0.16f),
                modifier = Modifier
                    .size(96.dp)
                    .border(2.dp, Color(0xFF00E676), CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Text(
                text = if (isVideo) "Gelen Görüntülü Arama" else "Gelen Sesli Arama",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            // Never show peer's secret nickname; show only their node code
            Text(
                text = incomingCall.callerSlot.slotId,
                fontFamily = ShanMonoFontFamily,
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF00E676)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Uçtan Uca Şifreli WebRTC Kanalı",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF8FA3B8)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onReject,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFFF5252),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(64.dp).testTag("reject_call_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Reddet", modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Reddet", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onAccept,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF00E676),
                            contentColor = Color(0xFF021A0E)
                        ),
                        modifier = Modifier.size(64.dp).testTag("accept_call_button")
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Kabul Et",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Kabul Et", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ActiveCallOverlay(
    callSession: ActiveCallSession,
    iceState: PeerConnection.IceConnectionState?,
    webRtcManager: ShanWebRtcManager?,
    onEndCall: () -> Unit
) {
    val isVideo = callSession.callType == ShanCallType.VIDEO
    var isMuted by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(isVideo) }
    var isVideoEnabled by remember { mutableStateOf(isVideo) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    val peerSlotCode = if (callSession.isIncoming) callSession.callerSlot.slotId else callSession.calleeSlot.slotId

    LaunchedEffect(callSession.callId, callSession.status) {
        if (callSession.status == ShanCallStatus.ACTIVE) {
            while (true) {
                delay(1000L)
                elapsedSec++
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050A10))
            .safeDrawingPadding()
    ) {
        if (isVideo && webRtcManager != null) {
            // Remote Fullscreen Video Renderer
            var remoteRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
            var localRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

            DisposableEffect(webRtcManager) {
                onDispose {
                    remoteRendererRef?.release()
                    localRendererRef?.release()
                }
            }

            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(webRtcManager.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        webRtcManager.attachRemoteRenderer(this)
                        remoteRendererRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Local PiP Video Renderer
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(webRtcManager.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(true)
                        setZOrderMediaOverlay(true)
                        setEnableHardwareScaler(true)
                        webRtcManager.attachLocalRenderer(this)
                        localRendererRef = this
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .size(width = 112.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Color(0xFF00E676), RoundedCornerShape(16.dp))
            )
        }

        // Top Call Status Banner
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xAA0B131C),
                modifier = Modifier.border(1.dp, Color(0xFF203247), RoundedCornerShape(20.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$peerSlotCode • ${
                            when {
                                iceState == PeerConnection.IceConnectionState.CONNECTED ||
                                    callSession.status == ShanCallStatus.ACTIVE ->
                                    String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                                else -> "Bağlanıyor..."
                            }
                        }",
                        fontFamily = ShanMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        if (!isVideo) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF00E676).copy(alpha = 0.16f),
                    modifier = Modifier
                        .size(110.dp)
                        .border(2.dp, Color(0xFF00E676), CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                Text(
                    text = peerSlotCode,
                    fontFamily = ShanMonoFontFamily,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Text(
                    text = "Uçtan Uca Şifreli Sesli Arama",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF8FA3B8)
                )
            }
        }

        // Bottom Controls Bar
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xD90B131C),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .fillMaxWidth()
                .border(1.dp, Color(0xFF203247), RoundedCornerShape(28.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = {
                        isMuted = !isMuted
                        webRtcManager?.setMicMuted(isMuted)
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isMuted) Color(0xFFFF5252) else Color(0xFF1A2838),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(52.dp).testTag("call_mute_button")
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mikrofon"
                    )
                }

                FilledIconButton(
                    onClick = {
                        isSpeaker = !isSpeaker
                        webRtcManager?.setSpeakerphoneEnabled(isSpeaker)
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isSpeaker) Color(0xFF00E676) else Color(0xFF1A2838),
                        contentColor = if (isSpeaker) Color(0xFF021A0E) else Color.White
                    ),
                    modifier = Modifier.size(52.dp).testTag("call_speaker_button")
                ) {
                    Icon(
                        imageVector = if (isSpeaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeDown,
                        contentDescription = "Hoparlör"
                    )
                }

                if (isVideo) {
                    FilledIconButton(
                        onClick = {
                            isVideoEnabled = !isVideoEnabled
                            webRtcManager?.setVideoEnabled(isVideoEnabled)
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (!isVideoEnabled) Color(0xFFFF5252) else Color(0xFF1A2838),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(52.dp).testTag("call_video_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Kamera"
                        )
                    }

                    FilledIconButton(
                        onClick = { webRtcManager?.switchCamera() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1A2838),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(52.dp).testTag("call_switch_camera_button")
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Kamera Değiştir")
                    }
                }

                FilledIconButton(
                    onClick = onEndCall,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFFF5252),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(56.dp).testTag("end_call_button")
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Aramayı Sonlandır")
                }
            }
        }
    }
}
