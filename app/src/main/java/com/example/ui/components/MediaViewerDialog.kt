package com.example.ui.components

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.DecryptedMessage
import com.example.model.ShanMessageType
import com.example.ui.theme.ShanMonoFontFamily
import java.nio.charset.StandardCharsets

/**
 * Fullscreen RAM-only Media & File Viewer.
 * Decrypted bytes are never written to disk, cache, or MediaStore.
 */
@Composable
fun MediaViewerDialog(
    message: DecryptedMessage,
    bytes: ByteArray,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF04070B))
                .safeDrawingPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = message.mediaFileName ?: "Şifreli Medya",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Yalnızca RAM'de görüntüleniyor • Cihazda depolanmaz",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00E676)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (message.msgType) {
                        ShanMessageType.IMAGE -> {
                            val bmp = remember(bytes) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = message.mediaFileName ?: "Fotoğraf",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text("Görsel çözülemedi", color = Color.White)
                            }
                        }

                        ShanMessageType.VIDEO -> {
                            RamVideoPlayer(bytes = bytes)
                        }

                        ShanMessageType.FILE, ShanMessageType.TEXT -> {
                            RamFilePreview(
                                fileName = message.mediaFileName ?: "dosya",
                                mimeType = message.mediaMimeType ?: "application/octet-stream",
                                bytes = bytes
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RamVideoPlayer(bytes: ByteArray) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    val dataSource = remember(bytes) {
        object : MediaDataSource() {
            override fun getSize(): Long = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val available = (bytes.size - position).toInt()
                val toRead = minOf(size, available)
                System.arraycopy(bytes, position.toInt(), buffer, offset, toRead)
                return toRead
            }
            override fun close() {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val mp = MediaPlayer().apply {
                                    setDataSource(dataSource)
                                    setDisplay(holder)
                                    isLooping = true
                                    setOnPreparedListener {
                                        it.start()
                                        isPlaying = true
                                    }
                                    prepareAsync()
                                }
                                mediaPlayer = mp
                            } catch (_: Exception) {
                            }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            try {
                                mediaPlayer?.release()
                            } catch (_: Exception) {
                            }
                            mediaPlayer = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            IconButton(
                onClick = {
                    val mp = mediaPlayer ?: return@IconButton
                    if (mp.isPlaying) {
                        mp.pause()
                        isPlaying = false
                    } else {
                        mp.start()
                        isPlaying = true
                    }
                }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Duraklat" else "Oynat",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun RamFilePreview(fileName: String, mimeType: String, bytes: ByteArray) {
    val previewText = remember(bytes, mimeType) {
        val isTextLike = mimeType.startsWith("text/") ||
            mimeType.contains("json") ||
            mimeType.contains("xml") ||
            fileName.endsWith(".txt", ignoreCase = true) ||
            fileName.endsWith(".md", ignoreCase = true) ||
            fileName.endsWith(".json", ignoreCase = true) ||
            fileName.endsWith(".csv", ignoreCase = true)
        if (isTextLike) {
            String(bytes.copyOfRange(0, minOf(bytes.size, 32_768)), StandardCharsets.UTF_8)
        } else {
            val hexSample = bytes.take(256).joinToString(" ") { "%02X".format(it) }
            "Dosya: $fileName\nMIME: $mimeType\nBoyut: ${bytes.size} bayt\n\n[Bellek İçi İkili Önizleme - İlk 256 Bayt]\n$hexSample"
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0E1722),
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = previewText,
                fontFamily = ShanMonoFontFamily,
                color = Color(0xFFEAF2FA),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
