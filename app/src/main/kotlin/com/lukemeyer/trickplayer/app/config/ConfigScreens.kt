package com.lukemeyer.trickplayer.app.config

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import com.lukemeyer.trickplayer.core.scene.Advance
import com.lukemeyer.trickplayer.core.source.BrowseItem
import com.lukemeyer.trickplayer.core.source.Container
import com.lukemeyer.trickplayer.core.source.Playable
import com.lukemeyer.trickplayer.core.source.ServerRef

/**
 * The configuration UI.
 *
 * Deliberately a stack of lists, and it stays that way with a second provider:
 * the flow is the same everywhere except where the providers genuinely differ,
 * which is sign-in. The only text the user ever supplies is a code typed on
 * some *other* device — plus, on Jellyfin alone, a server address, because
 * there is no account service that could know it.
 *
 * No preview here, and not a reduced one: **none**. On a phone a preview
 * answers "will this content survive my display", which needs several scenes
 * side by side and the picture controls next to them. On a 450 px circle there
 * is no picture control to judge and the only question left — "is this the
 * right episode" — is already answered by the title (UI.md §3).
 */

@Composable
fun ConfigScreen(state: ConfigViewModel.State, vm: ConfigViewModel) {
    Box(Modifier.fillMaxSize()) {
        TimeText()
        when (val step = state.step) {
            ConfigViewModel.Step.Loading -> Centered {
                Text(
                    "Connecting…",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
            }
            ConfigViewModel.Step.Sources -> Sources(state, vm)
            ConfigViewModel.Step.AddProvider -> AddProvider(state, vm)
            ConfigViewModel.Step.AddAddress -> AddAddress(vm)
            is ConfigViewModel.Step.Linking -> Linking(step, state, vm)
            ConfigViewModel.Step.Servers -> Servers(state, vm)
            ConfigViewModel.Step.Browse -> Browse(state, vm)
            is ConfigViewModel.Step.Items -> Items(step.title, state, vm)
            is ConfigViewModel.Step.Done -> Done(step, vm)
            ConfigViewModel.Step.Options -> Options(state.options, vm)
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
private fun ChipList(
    header: String,
    subtitle: String? = null,
    /** Every list gets one. A swipe works too, but nothing on screen says so. */
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
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
        if (onBack != null) {
            item {
                Chip(
                    label = { Text(backLabel) },
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Listed by SERVER name, not provider name — "Cubert", not "Plex". Users think
 * in servers, and the provider is a badge (UI.md §1).
 *
 * Only reachable with two or more. With one source this screen does not appear
 * at all: it is absent, not something to dismiss.
 */
@Composable
private fun Sources(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList("Servers", state.error, onBack = { vm.back() }) {
        items(state.sources) { rec: Map<String, String> ->
            Chip(
                label = { Text(rec["name"] ?: rec["server"].orEmpty(), maxLines = 1) },
                secondaryLabel = {
                    Text(if (rec["provider"] == "jellyfin") "Jellyfin" else "Plex")
                },
                onClick = { vm.openSource(rec) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                label = { Text("Add a server") },
                onClick = { vm.addSource() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

/** The only place a provider *type* is ever named to the user. */
@Composable
private fun AddProvider(state: ConfigViewModel.State, vm: ConfigViewModel) = Centered {
    Text("Add a server", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
    if (state.error != null) {
        Text(
            state.error,
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    Chip(
        label = { Text("Plex") },
        onClick = { vm.chooseProvider("plex") },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Chip(
        label = { Text("Jellyfin") },
        onClick = { vm.chooseProvider("jellyfin") },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * The one text field in the whole app, and only Jellyfin needs it: there is no
 * account service, so the address IS the identity and nothing can be
 * authenticated before it is known.
 *
 * Handed to the system's own input — voice, handwriting, or whatever keyboard
 * the watch has — rather than a Compose text field, because dictating "ten dot
 * twelve dot eighteen" is a far better experience than a 450 px keyboard. It is
 * asked for **once**: the address is saved with the credential, precisely so
 * nobody has to do this twice.
 */
@Composable
private fun AddAddress(vm: ConfigViewModel) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(ADDRESS_KEY)
                ?.toString()
                .orEmpty()
            vm.setAddress(text)
        }
    }

    Centered {
        Text("Jellyfin", style = MaterialTheme.typography.title3)
        Text(
            "Your server's address",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Chip(
            label = { Text("Enter address") },
            onClick = {
                val remoteInputs: List<RemoteInput> = listOf(
                    RemoteInput.Builder(ADDRESS_KEY).setLabel("Server address").build(),
                )
                val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                launcher.launch(intent)
            },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ADDRESS_KEY = "jellyfin_address"

/**
 * The whole reason a code-and-poll flow belongs on a watch: a short code, and
 * the typing happens somewhere else entirely.
 *
 * One screen for both providers, because the flows are the same shape (F-018).
 * Only [ConfigViewModel.Step.Linking.enterAt] differs, and it comes from the
 * provider because the user cannot guess it.
 */
@Composable
private fun Linking(
    step: ConfigViewModel.Step.Linking,
    state: ConfigViewModel.State,
    vm: ConfigViewModel,
) = Centered {
    Text("Go to", style = MaterialTheme.typography.caption2)
    Text(
        step.enterAt,
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        maxLines = 3,
    )
    Text(
        step.code,
        style = MaterialTheme.typography.display1,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    Text(
        state.error ?: "and enter this code",
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.onSurfaceVariant,
    )
    // Nothing may depend on a timer alone. The code is typed on another device,
    // so the watch may have dozed or this activity been stopped by the time the
    // user comes back — the poll loop can be seconds away or gone. This is the
    // escape hatch that lets them finish the flow themselves (F-018).
    Chip(
        onClick = { vm.checkNow() },
        label = { Text("I've entered it") },
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** Plex only. Jellyfin has no account service, so there is nothing to list. */
@Composable
private fun Servers(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList("Server", "${state.servers.size} found", onBack = { vm.back() }) {
        items(state.servers) { s: ServerRef ->
            Chip(
                label = { Text(s.name, maxLines = 1) },
                secondaryLabel = { Text("${s.routes.size} route(s)") },
                onClick = { vm.chooseServer(s) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

/**
 * Continue watching, playlists, libraries — **the same three roots as the other
 * two platforms**.
 *
 * The watch is not given a reduced set. The screen is small, but a three-item
 * list is not what makes a small screen hard, and diverging the information
 * architecture per platform is how the three builds drifted apart in the first
 * place (UI.md §2).
 */
@Composable
private fun Browse(state: ConfigViewModel.State, vm: ConfigViewModel) =
    ChipList(
        state.sourceName.ifEmpty { "Browse" },
        onBack = { vm.back() },
        backLabel = if (state.sources.size > 1) "Servers" else "Close",
    ) {
        itemsIndexed(state.roots) { i, root ->
            Chip(
                label = { Text(root.title, maxLines = 1) },
                secondaryLabel = if (i == 0) {
                    { Text("what you're watching") }
                } else null,
                // Continue watching first, and not as a nicety: the test account
                // has 357 shows, roughly forty-four swipes to the middle of the
                // alphabet (F-019).
                colors = if (i == 0) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                onClick = { vm.openContainer(root) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                label = { Text("Options") },
                onClick = { vm.showOptions() },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.itemsIndexed(
    list: List<Container>,
    content: @Composable androidx.wear.compose.foundation.lazy.ScalingLazyListItemScope.(Int, Container) -> Unit,
) = items(list.size) { i -> content(i, list[i]) }

/**
 * One level, whatever that level is: sub-containers first, then the items that
 * survive eligibility.
 *
 * Usable items appear as they are confirmed, with the scan still running
 * underneath — waiting for a whole show would be four seconds of nothing on
 * Plex (F-015).
 */
@Composable
private fun Items(title: String, state: ConfigViewModel.State, vm: ConfigViewModel) {
    val scanning = state.scanned < state.toScan
    ChipList(
        title,
        when {
            state.toScan == 0 -> null
            scanning -> "checking ${state.scanned}/${state.toScan} — ${state.playable.size} playable"
            // A container that is half ineligible says so rather than silently
            // appearing short (UI.md §2).
            else -> "${state.playable.size} of ${state.toScan} playable"
        },
        onBack = { vm.back() },
    ) {
        items(state.containers) { c: Container ->
            Chip(
                label = { Text(c.title, maxLines = 2) },
                secondaryLabel = c.subtitle.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                onClick = { vm.openContainer(c) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(state.playable) { (item: BrowseItem, play: Playable) ->
            Chip(
                label = { Text(item.title, maxLines = 2) },
                secondaryLabel = item.subtitle.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                onClick = { vm.choose(item, play) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.containers.isEmpty() && state.playable.isEmpty() && !scanning) {
            item {
                Text(
                    "Nothing here can be played yet — an item needs a trick-play " +
                        "index and subtitles.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/**
 * What is playing, and every way out of it.
 *
 * It used to say "Set" over a title in caption text, with one chip — which left
 * two questions unanswered at the moment they are most likely to be asked: *did
 * it take the thing I picked*, and *how do I stop*. The title now leads, the
 * server is named under it, and the three things anyone wants next are each a
 * full-width row: adjust it, choose something else, or stop.
 */
@Composable
private fun Done(step: ConfigViewModel.Step.Done, vm: ConfigViewModel) =
    ChipList("Now playing", onBack = { vm.back() }, backLabel = "Browse") {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )
                if (step.sourceName.isNotEmpty()) {
                    Text(
                        step.sourceName,
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item {
            Chip(
                label = { Text("Options") },
                onClick = { vm.showOptions() },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text("Pick another") },
                onClick = { vm.pickAnother() },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            // The way out. Without it the only way to stop the face showing an
            // episode is to pick a different one.
            Chip(
                label = { Text("Stop playing") },
                onClick = { vm.exitPlayback() },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

/**
 * The controls this platform gets, and no others.
 *
 * **No sliders.** A control that needs a preview to judge does not belong on a
 * 450 px circle, which is the same reason the picture group and bandwidth are
 * absent entirely rather than shrunk (UI.md §4.3). What is left is policy, and
 * policy reads as full-width rows with named choices.
 */
@Composable
private fun Options(options: ConfigViewModel.Options, vm: ConfigViewModel) =
    ChipList("Options", onBack = { vm.back() }) {
        item {
            ToggleChip(
                checked = options.skipSilent,
                onCheckedChange = { vm.setSkipSilent(it) },
                label = { Text("Skip silent scenes", maxLines = 2) },
                // "Subtitles" rather than "dialogue" because the filter keys on
                // CUES: a scene with unsubtitled speech is skipped too, and a
                // name promising otherwise would lie about the mechanism.
                secondaryLabel = {
                    Text(
                        when (options.hasCues) {
                            // On an item with no subtitle track this does
                            // nothing, because the filter floors rather than
                            // emptying the episode (F-009).
                            false -> "no subtitles here — no effect"
                            else -> "scenes with no subtitles"
                        },
                        maxLines = 2,
                    )
                },
                toggleControl = { Switch(checked = options.skipSilent) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { SectionLabel("On wake") }
        items(Advance.entries.toList()) { mode: Advance ->
            ChoiceRow(mode, options.onWake) { vm.setOnWake(mode) }
        }

        item { SectionLabel("On tap") }
        items(Advance.entries.toList()) { mode: Advance ->
            ChoiceRow(mode, options.onTap) { vm.setOnTap(mode) }
        }

        item { SectionLabel("Autoplay length") }
        items(AUTOPLAY_LENGTHS) { ms: Long ->
            Chip(
                label = { Text("${ms / 1000}s") },
                onClick = { vm.setAutoplayLength(ms) },
                colors = if (ms == options.autoplayLengthMs) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                // Not a style note: a burst longer than the screen timeout is
                // played to a dark screen and then CREDITED as watched, because
                // elapsed wall-clock time is how the position catches up.
                "Autoplay length should be shorter than screen timeout.",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        item {
            Text(
                // The one behaviour worth captioning rather than hiding.
                "\"Next subtitle\" rolls into the next scene when this one runs " +
                    "out of them.",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

@Composable
private fun SectionLabel(text: String) = ListHeader {
    Text(text, style = MaterialTheme.typography.caption1)
}

/** The lengths offered, 5 s to 30 s. Named choices, not a slider (UI.md §4.3). */
private val AUTOPLAY_LENGTHS = listOf(5_000L, 10_000L, 15_000L, 20_000L, 30_000L)

/** Named choices, not a slider over four positions with a number that means nothing. */
@Composable
private fun ChoiceRow(mode: Advance, selected: Advance, onClick: () -> Unit) {
    Chip(
        label = { Text(mode.label()) },
        onClick = onClick,
        colors = if (mode == selected) ChipDefaults.primaryChipColors()
            else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Never the implementation: not `SameScene`, not `NewScene` (UI.md §4.1). */
private fun Advance.label(): String = when (this) {
    Advance.NOTHING -> "Do nothing"
    Advance.NEXT_SUBTITLE -> "Next subtitle"
    Advance.NEXT_SCENE -> "Next scene"
    Advance.AUTOPLAY -> "Autoplay"
}
