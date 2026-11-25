package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.crypto.ShanSessionCrypto
import com.example.model.DecryptedMessage
import com.example.model.PeerPresenceStatus
import com.example.model.ShanCallType
import com.example.model.UiState
import com.example.notify.ShanNotificationHelper
import com.example.ui.ShanChatViewModel
import com.example.ui.components.ActiveCallOverlay
import com.example.ui.components.IncomingCallOverlay
import com.example.ui.components.MediaViewerDialog
import com.example.ui.components.MessageBubble
import com.example.ui.theme.LocalShanColors
import com.example.ui.theme.ShanMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ShanChatViewModel,
    sessionCrypto: ShanSessionCrypto,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSuppressNextBackgroundLock: (Boolean) -> Unit,
    onLockSession: () -> Unit
) {
    val context = LocalContext.current
    val shanColors = LocalShanColors.current

    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val peerPresence by viewModel.peerPresence.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val editTarget by viewModel.editTarget.collectAsStateWithLifecycle()
    val mediaCache by viewModel.mediaBytesRamCache.collectAsStateWithLifecycle()
    val loadingMediaIds by viewModel.loadingMediaIds.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val newRemoteMessageEvent by viewModel.newRemoteMessageEvent.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val incomingCall by viewModel.incomingCall.collectAsStateWithLifecycle()
    val callIceState by viewModel.callIceState.collectAsStateWithLifecycle()

    var composerText by remember { mutableStateOf("") }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var viewerItem by remember { mutableStateOf<Pair<DecryptedMessage, ByteArray>?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Populate input when editing a message
    LaunchedEffect(editTarget) {
        if (editTarget != null) {
            composerText = editTarget!!.text
        }
    }

    // Show error snackbars cleanly
    LaunchedEffect(actionError) {
        val err = actionError
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    // Discreet single-sound notification on incoming remote message
    LaunchedEffect(newRemoteMessageEvent) {
        if (newRemoteMessageEvent > 0L) {
            ShanNotificationHelper.postStealthNotification(context)
        }
    }

    // Runtime notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onSuppressNextBackgroundLock(false)
    }

    LaunchedEffect(Unit) {
        ShanNotificationHelper.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                onSuppressNextBackgroundLock(true)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Runtime permissions for Voice / Video calls
    val callPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onSuppressNextBackgroundLock(false)
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioOk) {
            pendingCallAction?.invoke()
        }
        pendingCallAction = null
    }

    fun launchWithCallPermissions(needsCamera: Boolean, onGranted: () -> Unit) {
        val perms = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (needsCamera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (perms.isEmpty()) {
            onGranted()
        } else {
            pendingCallAction = onGranted
            onSuppressNextBackgroundLock(true)
            callPermissionsLauncher.launch(perms.toTypedArray())
        }
    }

    // Zero-permission Photo/Video Picker
    val visualMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        onSuppressNextBackgroundLock(false)
        if (uri != null) {
            val caption = composerText
            composerText = ""
            viewModel.sendPickedMedia(context, uri, caption, isDocument = false)
        }
    }

    // Zero-permission File Picker (SAF)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        onSuppressNextBackgroundLock(false)
        if (uri != null) {
            val caption = composerText
            composerText = ""
            viewModel.sendPickedMedia(context, uri, caption, isDocument = true)
        }
    }

    // Auto-scroll to bottom on new messages
    val messageCount = (messagesState as? UiState.Success)?.data?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    val peerSlot = sessionCrypto.activeSlot.peerSlot()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, shanColors.border)
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(R.drawable.shan_logo),
                                contentDescription = stringResource(R.string.content_desc_shan_logo),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, shanColors.accentBronze, CircleShape),
                                contentScale = ContentScale.Crop
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            // Displays "Shan -Nick" using ONLY the logged-in user's own private nick
                            Text(
                                text = "${stringResource(R.string.app_name)} -${sessionCrypto.activeSlot.privateNick}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    launchWithCallPermissions(needsCamera = false) {
                                        viewModel.startOutgoingCall(context, ShanCallType.VOICE)
                                    }
                                },
                                modifier = Modifier.testTag("voice_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = stringResource(R.string.content_desc_voice_call),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = {
                                    launchWithCallPermissions(needsCamera = true) {
                                        viewModel.startOutgoingCall(context, ShanCallType.VIDEO)
                                    }
                                },
                                modifier = Modifier.testTag("video_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = stringResource(R.string.content_desc_video_call),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.markOfflineOnLock()
                                    onLockSession()
                                },
                                modifier = Modifier.testTag("lock_session_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.content_desc_lock_session),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showHeaderMenu = true },
                                    modifier = Modifier.testTag("header_more_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Diğer Seçenekler",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = showHeaderMenu,
                                    onDismissRequest = { showHeaderMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (isDarkTheme) "Aydınlık Tema" else "Karanlık Tema")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showHeaderMenu = false
                                            onToggleTheme()
                                        },
                                        modifier = Modifier.testTag("chat_theme_toggle")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Güvenlik Protokolü") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.VerifiedUser,
                                                contentDescription = null,
                                                tint = shanColors.accentCyan
                                            )
                                        },
                                        onClick = {
                                            showHeaderMenu = false
                                            showSecurityDialog = true
                                        },
                                        modifier = Modifier.testTag("security_info_button")
                                    )
                                }
                            }
                        }

                        // Horizontal Peer ID & Presence Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Shows ONLY peer node code, NEVER peer's private nick!
                            Text(
                                text = peerSlot.slotId,
                                fontFamily = ShanMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            PeerPresenceLine(presence = peerPresence)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, shanColors.border)
                ) {
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        AnimatedVisibility(visible = peerPresence?.isTyping == true) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(shanColors.emeraldGlow)
                                )
                                Text(
                                    text = "${peerSlot.slotId} yazıyor...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = shanColors.emeraldGlow,
                                    modifier = Modifier.testTag("typing_indicator_banner")
                                )
                            }
                        }

                        AnimatedVisibility(visible = isSending) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }

                        // Reply / Edit Banner
                        val activeReply = replyTarget
                        val activeEdit = editTarget
                        if (activeReply != null || activeEdit != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (activeEdit != null) Icons.Default.Edit else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (activeEdit != null) {
                                                "Mesajı Düzenle"
                                            } else {
                                                "Alıntı: ${activeReply?.senderSlot?.slotId ?: ""}"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = activeEdit?.text ?: activeReply?.text.orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (activeEdit != null) composerText = ""
                                            viewModel.setReplyTarget(null)
                                            viewModel.setEditTarget(null)
                                        }
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "İptal")
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    onSuppressNextBackgroundLock(true)
                                    visualMediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                enabled = !isSending,
                                modifier = Modifier.testTag("attach_media_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = stringResource(R.string.content_desc_attach_media),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = {
                                    onSuppressNextBackgroundLock(true)
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                enabled = !isSending,
                                modifier = Modifier.testTag("attach_file_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = stringResource(R.string.content_desc_attach_file),
                                    tint = shanColors.accentCyan
                                )
                            }

                            OutlinedTextField(
                                value = composerText,
                                onValueChange = {
                                    composerText = it
                                    viewModel.onComposerTextChanged(it)
                                },
                                placeholder = {
                                    Text(
                                        text = "Şifreli mesaj yaz...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(22.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = shanColors.border
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("message_input")
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            FilledIconButton(
                                onClick = {
                                    val msg = composerText
                                    composerText = ""
                                    viewModel.sendOrEditText(msg)
                                },
                                enabled = composerText.isNotBlank() && !isSending,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.content_desc_send_message)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (val state = messagesState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    is UiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    is UiState.Success -> {
                        if (state.data.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(42.dp)
                                    )
                                    Text(
                                        text = "Uçtan Uca Şifreli Kanal Hazır",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Tüm mesajlar ve paylaşımlar 24 saat sonra otomatik silinir. Cihazda hiçbir medya depolanmaz.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                items(
                                    items = state.data,
                                    key = { it.id }
                                ) { msg ->
                                    MessageBubble(
                                        message = msg,
                                        mediaBytes = mediaCache[msg.id],
                                        isMediaLoading = msg.id in loadingMediaIds,
                                        onRequestMediaLoad = { viewModel.loadMediaIntoRam(it) },
                                        onOpenMediaViewer = { item, bytes ->
                                            viewerItem = item to bytes
                                        },
                                        onReply = { viewModel.setReplyTarget(it) },
                                        onEdit = { viewModel.setEditTarget(it) },
                                        onToggleReaction = { m, emoji -> viewModel.toggleReaction(m, emoji) },
                                        onDelete = { viewModel.deleteMessage(it.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen RAM-Only Media Viewer
        viewerItem?.let { (msg, bytes) ->
            MediaViewerDialog(
                message = msg,
                bytes = bytes,
                onDismiss = { viewerItem = null }
            )
        }

        // Security Protocol Verification Dialog
        if (showSecurityDialog) {
            AlertDialog(
                onDismissRequest = { showSecurityDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text("Shan E2EE Güvenlik Durumu") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Anahtar Değişimi: ECDH P-256 (secp256r1)\nKDF: HKDF-HMAC-SHA256\nŞifreleme: AES-256-GCM (128-bit AEAD)",
                            fontFamily = ShanMonoFontFamily,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Kendi Parmak İzin (${sessionCrypto.activeSlot.slotId}):\n${sessionCrypto.myFingerprint}",
                            fontFamily = ShanMonoFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Karşı Taraf (${peerSlot.slotId}):\n${peerPresence?.peerFingerprint ?: "Ön kayıtlı anahtar aktif"}",
                            fontFamily = ShanMonoFontFamily,
                            fontSize = 11.sp,
                            color = shanColors.accentCyan
                        )
                        Text(
                            text = "• Ekran görüntüsü ve ekran kaydı FLAG_SECURE ile engellidir.\n• Mesajlar 24 saat sonra otomatik silinir.\n• Fotoğraf, video ve dosyalar yalnızca RAM'de çözülür, diske yazılmaz.\n• Arka plana geçildiğinde oturum anında kilitlenir.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSecurityDialog = false }) {
                        Text("Tamam")
                    }
                }
            )
        }

        // Incoming Call Fullscreen Overlay
        incomingCall?.let { call ->
            if (activeCall == null) {
                IncomingCallOverlay(
                    incomingCall = call,
                    onAccept = {
                        launchWithCallPermissions(needsCamera = call.callType == ShanCallType.VIDEO) {
                            viewModel.acceptIncomingCall(context)
                        }
                    },
                    onReject = { viewModel.rejectIncomingCall() }
                )
            }
        }

        // Active Voice / Video Call Fullscreen Overlay
        activeCall?.let { call ->
            ActiveCallOverlay(
                callSession = call,
                iceState = callIceState,
                webRtcManager = viewModel.getActiveWebRtcManager(),
                onEndCall = { viewModel.endActiveCall() }
            )
        }
    }
}

@Composable
private fun PeerPresenceLine(presence: PeerPresenceStatus?) {
    val shanColors = LocalShanColors.current
    val statusText = remember(presence) {
        when {
            presence == null -> "Bağlantı bekleniyor"
            presence.isTyping -> "yazıyor..."
            presence.isOnline -> "Çevrimiçi"
            presence.lastActiveMillis <= 0L -> "Son görülme: Bilinmiyor"
            else -> "Son görülme: ${formatLastSeenRange(presence.lastActiveMillis)}"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (presence?.isTyping == true || presence?.isOnline == true) shanColors.emeraldGlow
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (presence?.isTyping == true || presence?.isOnline == true) shanColors.emeraldGlow
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Formats last-seen timestamp as an exact 5-minute activity interval (e.g. "Bugün 14:20 – 14:25").
 */
private fun formatLastSeenRange(lastActiveMillis: Long): String {
    val windowMs = 5 * 60 * 1000L
    val bucketStart = (lastActiveMillis / windowMs) * windowMs
    val bucketEnd = bucketStart + windowMs
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFmt = SimpleDateFormat("dd MMM", Locale("tr"))

    val todayFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val isToday = todayFmt.format(Date(lastActiveMillis)) == todayFmt.format(Date())
    val prefix = if (isToday) "Bugün" else dayFmt.format(Date(lastActiveMillis))
    return "$prefix ${timeFmt.format(Date(bucketStart))} – ${timeFmt.format(Date(bucketEnd))}"
}
