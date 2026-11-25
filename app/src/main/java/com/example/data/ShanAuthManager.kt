package com.example.data

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ShanAuthManager {
    private const val TAG = "Auth"

    fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser)
        }
        addAuthStateListener(listener)
        awaitClose { removeAuthStateListener(listener) }
    }

    fun attemptAutoSignIn(
        context: Any? = null,
        credentialManager: Any? = null,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        scope.launch {
            onAuthSuccess()
        }
    }

    fun onGoogleSignInClicked(
        context: Any? = null,
        credentialManager: Any? = null,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: (String?) -> Unit = {}
    ) {
        scope.launch {
            onAuthSuccess()
        }
    }

    fun signOut(
        credentialManager: Any? = null,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    Firebase.auth.signOut()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sign out: ${e.message}")
                } finally {
                    onSignOutComplete()
                }
            }
        }
    }
}
