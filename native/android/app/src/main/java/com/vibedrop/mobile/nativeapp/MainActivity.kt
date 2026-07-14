package com.vibedrop.mobile.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vibedrop.mobile.nativeapp.data.AppContainer
import com.vibedrop.mobile.nativeapp.ui.VibeDropApp
import com.vibedrop.mobile.nativeapp.ui.theme.VibeDropTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { AppContainer(this) }
    private val _sharedPayload = MutableStateFlow<IncomingSharePayload?>(null)
    val sharedPayload: StateFlow<IncomingSharePayload?> = _sharedPayload

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            VibeDropTheme {
                VibeDropApp(appContainer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    fun clearSharedPayload(payloadId: Long) {
        if (_sharedPayload.value?.id == payloadId) {
            _sharedPayload.value = null
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val uris = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (text.isNullOrBlank() && uris.isEmpty()) return

        _sharedPayload.value = IncomingSharePayload(
            id = System.currentTimeMillis(),
            text = text?.takeIf { it.isNotBlank() },
            uris = uris,
            mimeType = intent.type
        )
    }
}

data class IncomingSharePayload(
    val id: Long,
    val text: String?,
    val uris: List<Uri>,
    val mimeType: String?
)
