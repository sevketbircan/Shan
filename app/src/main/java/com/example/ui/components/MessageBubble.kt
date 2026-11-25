package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DecryptedMessage
import com.example.model.ShanMessageType
import com.example.ui.theme.LocalShanColors
import com.example.ui.theme.ShanMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_REACTIONS = listOf("❤️", "🔥", "😂", "👍", "👀", "🔒")

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: DecryptedMessage,
    mediaBytes: ByteArray?,
    isMediaLoading: Boolean,
    onRequestMediaLoad: (String) -> Unit,
    onOpenMediaViewer: (DecryptedMessage, ByteArray) -> Unit,
    onReply: (DecryptedMessage) -> Unit,
    onEdit: (DecryptedMessage) -> Unit,
    onToggleReaction: (DecryptedMessage, String) -> Unit,
    onDelete: (DecryptedMessage) -> Unit
) {
    val shanColors = LocalShanColors.current
    var showActionMenu by remember { mutableStateOf(false) }

    LaunchedEffect(message.id, message.msgType) {
        if (message.msgType == ShanMessageType.IMAGE && mediaBytes == null && !isMediaLoading) {
            onRequestMediaLoad(message.id)
        }
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (message.isMine) 18.dp else 4.dp,
        bottomEnd = if (message.isMine) 4.dp else 18.dp
    )
    val bubbleColor = if (message.isMine) shanColors.outgoingBubble else shanColors.incomingBubble

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 310.dp)
                    .border(1.dp, shanColors.border, bubbleShape)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = { showActionMenu = true },
                        onLongClick = { showActionMenu = true }
                    )
                    .testTag("message_bubble_${message.id}")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Quoted Reply Block
                    if (!message.replyPreview.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = message.replySenderSlot?.slotId ?: "Alıntı",
                                    fontFamily = ShanMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = message.replyPreview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Media Content (Strictly In-Memory RAM)
                    when (message.msgType) {
                        ShanMessageType.IMAGE -> {
                            val imageBitmap = remember(mediaBytes) {
                                mediaBytes?.let { bytes ->
                                    try {
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .combinedClickable(
                                        onClick = {
                                            if (mediaBytes != null) {
                                                onOpenMediaViewer(message, mediaBytes)
                                            } else {
                                                onRequestMediaLoad(message.id)
                                            }
                                        },
                                        onLongClick = { showActionMenu = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (imageBitmap != null && mediaBytes != null) {
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = message.mediaFileName ?: "Şifreli Fotoğraf",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (isMediaLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                } else {
                                    Text(
                                        text = "Fotoğrafı RAM'de Çöz",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        ShanMessageType.VIDEO -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .combinedClickable(
                                        onClick = {
                                            if (mediaBytes != null) {
                                                onOpenMediaViewer(message, mediaBytes)
                                            } else {
                                                onRequestMediaLoad(message.id)
                                            }
                                        },
                                        onLongClick = { showActionMenu = true }
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMediaLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Şifreli Video",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.mediaFileName ?: "Şifreli Video",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (mediaBytes != null) {
                                            "RAM'de hazır • Oynatmak için dokun"
                                        } else {
                                            "${formatByteSize(message.mediaSizeBytes)} • Şifreli Video"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        ShanMessageType.FILE -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .combinedClickable(
                                        onClick = {
                                            if (mediaBytes != null) {
                                                onOpenMediaViewer(message, mediaBytes)
                                            } else {
                                                onRequestMediaLoad(message.id)
                                            }
                                        },
                                        onLongClick = { showActionMenu = true }
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMediaLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(26.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AttachFile,
                                        contentDescription = "Şifreli Dosya",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.mediaFileName ?: "Şifreli Dosya",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${formatByteSize(message.mediaSizeBytes)} • Cihazda depolanmaz",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        ShanMessageType.TEXT -> Unit
                    }

                    // Message Text / Caption
                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Metadata Row: Lock, Edited, Timestamp, 24h Remaining TTL
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = formatMessageTime(message.createdAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (message.edited) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• düzenlendi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = formatRemainingTtl(message.expiresAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Emoji Reactions Row
                    if (message.reactions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.reactions.values.groupingBy { it }.eachCount().forEach { (emoji, count) ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .border(1.dp, shanColors.border, RoundedCornerShape(10.dp))
                                        .clickable { onToggleReaction(message, emoji) }
                                ) {
                                    Text(
                                        text = if (count > 1) "$emoji $count" else emoji,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Menu: Quick Emoji Reactions + Alıntıla + Düzenle + Sil
            DropdownMenu(
                expanded = showActionMenu,
                onDismissRequest = { showActionMenu = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showActionMenu = false
                                    onToggleReaction(message, emoji)
                                }
                                .padding(6.dp)
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Alıntıla") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    onClick = {
                        showActionMenu = false
                        onReply(message)
                    }
                )
                if (message.isMine && message.msgType == ShanMessageType.TEXT) {
                    DropdownMenuItem(
                        text = { Text("Düzenle") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showActionMenu = false
                            onEdit(message)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Mesajı Sil", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showActionMenu = false
                        onDelete(message)
                    },
                    modifier = Modifier.testTag("delete_message_${message.id}")
                )
            }
        }
    }
}

private fun formatMessageTime(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun formatRemainingTtl(expiresAtMillis: Long): String {
    val diff = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val hours = diff / (60 * 60 * 1000L)
    val mins = (diff % (60 * 60 * 1000L)) / (60 * 1000L)
    return if (hours > 0) "${hours}s ${mins}d" else "${mins}d"
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
}
