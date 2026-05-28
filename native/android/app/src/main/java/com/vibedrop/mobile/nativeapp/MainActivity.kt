package com.vibedrop.mobile.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vibedrop.mobile.nativeapp.data.AppContainer
import com.vibedrop.mobile.nativeapp.ui.VibeDropApp
import com.vibedrop.mobile.nativeapp.ui.theme.VibeDropTheme

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VibeDropTheme {
                VibeDropApp(appContainer)
            }
        }
    }
}
