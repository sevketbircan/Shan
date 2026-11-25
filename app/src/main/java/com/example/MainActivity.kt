package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.crypto.ShanSessionCrypto
import com.example.data.ShanAuthManager.authStateFlow
import com.example.data.ShanRepository
import com.example.ui.ShanChatViewModel
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.CloudTunnelGateScreen
import com.example.ui.screens.HackerBootScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.ShanTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording, and recents thumbnails on physical devices.
        // Streaming emulators capture via virtual display, which SurfaceFlinger blanks to black if FLAG_SECURE is set.
        if (!isEmulatorDevice()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        enableEdgeToEdge()
        setContent {
            ShanAppRoot()
        }
    }

    private fun isEmulatorDevice(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val hardware = android.os.Build.HARDWARE.lowercase()
        val product = android.os.Build.PRODUCT.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val brand = android.os.Build.BRAND.lowercase()
        val device = android.os.Build.DEVICE.lowercase()
        val board = android.os.Build.BOARD.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("cuttlefish") ||
            fingerprint.contains("vsoc") ||
            fingerprint.contains("sdk_phone") ||
            fingerprint.contains("sdk_gphone") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            model.contains("cuttlefish") ||
            model.contains("sdk_phone") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("cutf") ||
            hardware.contains("vsoc") ||
            board.contains("goldfish") ||
            board.contains("ranchu") ||
            board.contains("cutf") ||
            product.contains("sdk_gphone") ||
            product.contains("sdk_phone") ||
            product.contains("cf_") ||
            product.contains("aosp_cf") ||
            product.contains("cutf") ||
            product.contains("vsoc") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            product.contains("vbox") ||
            device.contains("vsoc") ||
            device.contains("cutf") ||
            device.contains("emulator") ||
            manufacturer.contains("genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic"))
    }
}

@Composable
fun ShanAppRoot(auth: FirebaseAuth = Firebase.auth) {
    val systemDark = isSystemInDarkTheme()
    var darkThemeOverride by remember { mutableStateOf<Boolean?>(true) } // Default sleek dark mode with full Light/Dark toggle
    val isDarkTheme = darkThemeOverride ?: systemDark

    var activeSession by remember { mutableStateOf<ShanSessionCrypto?>(null) }
    var bootAnimationDone by remember { mutableStateOf(false) }
    var suppressNextBackgroundLock by remember { mutableStateOf(false) }
    var showLaunchSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200L)
        showLaunchSplash = false
    }

    val currentFirebaseUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (suppressNextBackgroundLock) {
                    suppressNextBackgroundLock = false
                } else {
                    // Lock immediately whenever the app moves to the background; require password on return
                    activeSession = null
                    bootAnimationDone = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ShanTheme(darkTheme = isDarkTheme) {
        val session = activeSession
        Crossfade(
            targetState = showLaunchSplash,
            animationSpec = tween(durationMillis = 350),
            label = "launch_splash_crossfade"
        ) { showingSplash ->
            if (showingSplash && session == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF020A17))
                ) {
                    Image(
                        painter = painterResource(R.drawable.shan_splash_vertical),
                        contentDescription = stringResource(R.string.content_desc_shan_logo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                when {
                    session == null -> {
                        LoginScreen(
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { darkThemeOverride = !isDarkTheme },
                            onAuthenticated = { newSession ->
                                bootAnimationDone = false
                                activeSession = newSession
                            }
                        )
                    }

            !bootAnimationDone -> {
                BackHandler {
                    activeSession = null
                    bootAnimationDone = false
                }
                HackerBootScreen(
                    sessionCrypto = session,
                    onBootComplete = { bootAnimationDone = true }
                )
            }

            currentFirebaseUser == null -> {
                BackHandler {
                    activeSession = null
                    bootAnimationDone = false
                }
                CloudTunnelGateScreen(
                    privateNick = session.activeSlot.privateNick,
                    onAuthSuccess = { /* AuthStateListener updates currentFirebaseUser automatically */ },
                    onLockBackToLogin = {
                        activeSession = null
                        bootAnimationDone = false
                    },
                    onSuppressNextBackgroundLock = { suppress ->
                        suppressNextBackgroundLock = suppress
                    }
                )
            }

            else -> {
                val uid = currentFirebaseUser!!.uid
                key(uid, session.activeSlot.slotId) {
                    val chatViewModel: ShanChatViewModel = viewModel(
                        key = "${uid}_${session.activeSlot.slotId}",
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY]) {
                                    "APPLICATION_KEY missing from CreationExtras"
                                }
                                val databaseId = app.getString(R.string.firestore_database_id)
                                val db = FirebaseFirestore.getInstance(databaseId)
                                ShanChatViewModel(
                                    repository = ShanRepository(db, auth),
                                    crypto = session,
                                    currentAuthUid = uid
                                )
                            }
                        }
                    )

                    BackHandler {
                        chatViewModel.markOfflineOnLock()
                        activeSession = null
                        bootAnimationDone = false
                    }

                    ChatScreen(
                        viewModel = chatViewModel,
                        sessionCrypto = session,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { darkThemeOverride = !isDarkTheme },
                        onSuppressNextBackgroundLock = { suppress ->
                            suppressNextBackgroundLock = suppress
                        },
                        onLockSession = {
                            activeSession = null
                            bootAnimationDone = false
                        }
                    )
                }
            }
        }
        }
        }
    }
}
