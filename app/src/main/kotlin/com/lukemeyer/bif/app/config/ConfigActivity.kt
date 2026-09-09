package com.lukemeyer.bif.app.config

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme

/**
 * The configuration app: sign in to Plex, pick a server, pick an episode.
 *
 * Separate from the watch face by necessity — the face is a Watch Face Format
 * bundle with no code at all — and separate from the complication services by
 * design, since nothing here may run on the 100 ms data-source path.
 */
class ConfigActivity : ComponentActivity() {

    private val vm: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                ConfigScreen(state, vm)
            }
        }
    }

    /** Back steps through the list stack before leaving the app. */
    @Deprecated("Wear OS swipe-to-dismiss routes through this")
    override fun onBackPressed() {
        val step = vm.state.value.step
        val nested = step is ConfigViewModel.Step.Items ||
            step == ConfigViewModel.Step.Shows ||
            step == ConfigViewModel.Step.Libraries
        if (nested) vm.back() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
