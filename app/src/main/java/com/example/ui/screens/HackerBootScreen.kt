package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.ShanSessionCrypto
import com.example.ui.theme.ShanMonoFontFamily
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalGreenDim
import com.example.ui.theme.TerminalGreenMid
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class BootStage {
    CMD_STREAM,
    LOADING_NICK,
    SUCCESS_BANNER
}

@Composable
fun HackerBootScreen(
    sessionCrypto: ShanSessionCrypto,
    onBootComplete: () -> Unit
) {
    var stage by remember { mutableStateOf(BootStage.CMD_STREAM) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    val terminalLines = remember { mutableStateListOf<String>() }
    var matrixTick by remember { mutableIntStateOf(0) }

    val algorithmLogs = remember(sessionCrypto.activeSlot) {
        listOf(
            "root@shan-vault:~# ./init_e2ee --node ${sessionCrypto.activeSlot.slotId}",
            "[OK] SHA-256 credential digest verified in constant time",
            "[CRYPTO] Generating ECDH P-256 (secp256r1) scalar in volatile RAM...",
            "[CRYPTO] PubKey Fingerprint: ${sessionCrypto.myFingerprint}",
            "[KDF] HKDF-HMAC-SHA256 extract(ikm, salt_128) -> 256-bit key ready",
            "[CIPHER] AES/GCM/NoPadding 96-bit IV + 128-bit AEAD tag active",
            "[SEC] FLAG_SECURE enabled (screenshots & recents blocked)",
            "[STORAGE] Zero-disk persistence mode: ALL MEDIA IN RAM ONLY",
            "[TTL] Ephemeral 24h auto-purge daemon attached",
            "0x4F 0xA9 0x1C 0x7E 0x92 0xD3 0x0B 0xF1 :: HANDSHAKE_LOCKED"
        )
    }

    LaunchedEffect(sessionCrypto) {
        // Stage 1: Flowing green algorithms on black CMD (~2.2s)
        for (line in algorithmLogs) {
            terminalLines.add(line)
            delay(190L)
        }
        delay(250L)

        // Stage 2: Centered "Loading..." with ONLY the active user's private nick for 4 seconds (4000ms)
        stage = BootStage.LOADING_NICK
        val steps = 40
        for (i in 1..steps) {
            loadingProgress = i / steps.toFloat()
            delay(100L) // 40 * 100ms = 4000ms (4 seconds)
        }

        // Stage 3: "Sisteme Başarıyla Giriş Yapıldı" (~1.3s)
        stage = BootStage.SUCCESS_BANNER
        delay(1300L)
        onBootComplete()
    }

    LaunchedEffect(Unit) {
        while (true) {
            matrixTick++
            delay(85L)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val glyphs = remember { "01ABCDEF#%&@*CRYPTOECDH256GCM".toCharArray() }
    val columns = remember { List(22) { Random.nextInt(0, 32) } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .safeDrawingPadding()
    ) {
        // Matrix green algorithm rain background
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val colWidth = size.width / columns.size
            val rowHeight = 28.dp.toPx().coerceAtLeast(1f)
            val maxRows = (size.height / rowHeight).toInt().coerceAtLeast(1)

            columns.forEachIndexed { colIdx, startOffset ->
                val headRow = (startOffset + matrixTick) % (maxRows + 8)
                val x = colIdx * colWidth + 6f
                if (x >= size.width - 12f) return@forEachIndexed
                for (r in 0..8) {
                    val row = headRow - r
                    val y = row * rowHeight
                    if (row >= 0 && y + rowHeight <= size.height) {
                        val glyphIdx = ((colIdx * 7 + row * 13 + matrixTick) % glyphs.size + glyphs.size) % glyphs.size
                        val ch = glyphs[glyphIdx].toString()
                        val alpha = ((1f - r / 9f) * 0.48f).coerceIn(0.05f, 0.5f)
                        val color = if (r == 0) TerminalGreenBright.copy(alpha = 0.72f)
                        else TerminalGreenMid.copy(alpha = alpha)
                        val layoutResult = textMeasurer.measure(
                            text = ch,
                            style = TextStyle(
                                fontFamily = ShanMonoFontFamily,
                                fontSize = 13.sp
                            )
                        )
                        drawText(
                            textLayoutResult = layoutResult,
                            color = color,
                            topLeft = Offset(x, y)
                        )
                    }
                }
            }
        }

        // Top CMD Terminal Stream
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC061209), RoundedCornerShape(10.dp))
                    .border(1.dp, TerminalGreenDim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = TerminalGreenBright,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SHAN_CMD // E2EE_KERNEL v2.5",
                    fontFamily = ShanMonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TerminalGreenBright
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            AnimatedVisibility(visible = stage == BootStage.CMD_STREAM) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xD9030905), RoundedCornerShape(12.dp))
                        .border(1.dp, TerminalGreenDim, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    terminalLines.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = ShanMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = TerminalGreenBright
                        )
                    }
                }
            }
        }

        // Centered 4-Second Loading with Private Nick ONLY & Final Success Message
        if (stage == BootStage.LOADING_NICK || stage == BootStage.SUCCESS_BANNER) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xEB051008),
                    modifier = Modifier
                        .padding(28.dp)
                        .border(1.5.dp, TerminalGreenBright.copy(alpha = pulseAlpha), RoundedCornerShape(22.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (stage == BootStage.LOADING_NICK) {
                            CircularProgressIndicator(
                                color = TerminalGreenBright,
                                trackColor = TerminalGreenDim,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(46.dp)
                            )

                            // Displays ONLY the logged-in user's private nick ("Aslı" or "Şevket")
                            Text(
                                text = sessionCrypto.activeSlot.privateNick,
                                fontFamily = ShanMonoFontFamily,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = TerminalGreenBright
                            )

                            Text(
                                text = "Loading...",
                                fontFamily = ShanMonoFontFamily,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = TerminalGreenBright.copy(alpha = pulseAlpha)
                            )

                            LinearProgressIndicator(
                                progress = { loadingProgress },
                                color = TerminalGreenBright,
                                trackColor = TerminalGreenDim,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )

                            Text(
                                text = "ECDH-P256 + AES-256-GCM • ${(loadingProgress * 100).toInt()}%",
                                fontFamily = ShanMonoFontFamily,
                                fontSize = 11.sp,
                                color = TerminalGreenMid
                            )
                        } else {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + scaleIn()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = TerminalGreenBright,
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Text(
                                        text = sessionCrypto.activeSlot.privateNick,
                                        fontFamily = ShanMonoFontFamily,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TerminalGreenMid
                                    )
                                    Text(
                                        text = "Sisteme Başarıyla Giriş Yapıldı",
                                        fontFamily = ShanMonoFontFamily,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TerminalGreenBright,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
