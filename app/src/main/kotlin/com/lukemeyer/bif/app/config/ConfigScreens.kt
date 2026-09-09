package com.lukemeyer.bif.app.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.lukemeyer.bif.core.plex.PlexLibrary
import com.lukemeyer.bif.core.plex.PlexTv

/**
 * The configuration UI.
 *
 * Deliberately a stack of lists. There is no keyboard here and there does not
 * need to be: the only text the user ever supplies is a four-character code
 * typed on some *other* device.
 */

@Composable
fun ConfigScreen(state: ConfigViewModel.State, vm: ConfigViewModel) {
    Box(Modifier.fillMaxSize()) {
        TimeText()
        when (val step = state.step) {
            ConfigViewModel.Step.SignIn -> SignIn(state, vm)
            is ConfigViewModel.Step.Linking -> Linking(step.code)
            ConfigViewModel.Step.Servers -> Servers(state, vm)
            ConfigViewModel.Step.Libraries -> Libraries(state, vm)
            ConfigViewModel.Step.Shows -> Shows(state, vm)
            is ConfigViewModel.Step.Items -> Items(step.showTitle, state, vm)
            is ConfigViewModel.Step.Done -> Done(step.title, vm)
        }
        if (state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun SignIn(state: ConfigViewModel.State, vm: ConfigViewModel) = Centered {
    Text("BIF Watchface", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
    Text(
        state.error ?: "Connect your Plex account",
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        color = if (state.error != null) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Chip(
        label = { Text("Sign in") },
        onClick = { vm.signIn() },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The whole reason the PIN flow belongs on a watch: a four-character code, and
 * the typing happens somewhere else entirely.
 */
@Composable
private fun Linking(code: String) = Centered {
    Text("Go to", style = MaterialTheme.typography.caption2)
    Text("plex.tv/link", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
    Text(
        code,
        style = MaterialTheme.typography.display1,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    Text(
        "and enter this code",
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun ChipList(
    header: String,
    subtitle: String? = null,
    content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
    ) {
        item {
            ListHeader {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(header, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        content()
    }
}

@Composable
private fun Servers(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList("Server", "${state.servers.size} found") {
        items(state.servers) { s: PlexTv.Server ->
            Chip(
                label = { Text(s.name, maxLines = 1) },
                secondaryLabel = { Text("${s.connections.size} route(s)") },
                onClick = { vm.chooseServer(s) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

@Composable
private fun Libraries(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList("Library") {
        // First, because it is nearly always the answer. A 357-show library is
        // forty-odd swipes deep; On Deck is what you are already watching.
        item {
            Chip(
                label = { Text("On Deck") },
                secondaryLabel = { Text("what you're watching") },
                onClick = { vm.chooseOnDeck() },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(state.sections) { sec: PlexLibrary.Section ->
            Chip(
                label = { Text(sec.title, maxLines = 1) },
                secondaryLabel = { Text(sec.type) },
                onClick = { vm.chooseSection(sec) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

@Composable
private fun Shows(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList("Show", "${state.shows.size}") {
        items(state.shows) { show: PlexLibrary.Item ->
            Chip(
                label = { Text(show.title, maxLines = 2) },
                onClick = { vm.chooseShow(show) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

/**
 * Usable episodes appear as they are confirmed, with the scan still running
 * underneath. Waiting for a whole show would be four seconds of nothing.
 */
@Composable
private fun Items(title: String, state: ConfigViewModel.State, vm: ConfigViewModel) {
    val scanning = state.scanned < state.toScan
    ChipList(
        title,
        if (scanning) "checking ${state.scanned}/${state.toScan} — ${state.playable.size} usable"
        else "${state.playable.size} usable of ${state.toScan}",
    ) {
        items(state.playable) { (item, play) ->
            Chip(
                label = { Text(item.title, maxLines = 2) },
                secondaryLabel = { Text("${item.subtitle}  ${play.subLanguage}") },
                onClick = { vm.choose(item, play) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.playable.isEmpty() && !scanning) {
            item {
                Text(
                    "None of these have both a trick-play index and sidecar subtitles.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Done(title: String, vm: ConfigViewModel) = Centered {
    Text("Set", style = MaterialTheme.typography.title3)
    Text(
        title,
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    // Without a way out this screen is a dead end and changing episode means
    // force-stopping the app.
    Chip(
        label = { Text("Pick another") },
        onClick = { vm.pickAnother() },
        modifier = Modifier.fillMaxWidth(),
    )
}
