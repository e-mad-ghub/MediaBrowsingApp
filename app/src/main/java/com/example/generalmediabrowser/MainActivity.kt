package com.example.generalmediabrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import com.example.generalmediabrowser.media.MediaSessionRepository
import com.example.generalmediabrowser.ui.MediaControlScreen
import com.example.generalmediabrowser.ui.MediaControlViewModel
import com.example.generalmediabrowser.ui.MediaControlViewModelFactory
import com.example.generalmediabrowser.ui.theme.GeneralMediaBrowserTheme

class MainActivity : ComponentActivity() {
    private val mediaControlViewModel: MediaControlViewModel by viewModels {
        MediaControlViewModelFactory(MediaSessionRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeneralMediaBrowserTheme {
                MediaControlScreen(
                    viewModel = mediaControlViewModel,
                    onRequestNotificationAccess = { openNotificationAccessSettings() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Some OEMs require direct package access; fall back to app details if needed.
        val resolved = intent.resolveActivity(packageManager)
        if (resolved != null) {
            startActivity(intent)
        } else {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }
}
