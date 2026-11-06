// The idea of picking a headphone from AutoEq's list and fitting a correction to it is from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.
// The measurements are from the AutoEq project (MIT, https://github.com/jaakkopasanen/AutoEq).

package com.spotifry.music.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.AutoEqSource
import com.spotifry.music.data.Settings
import com.spotifry.music.player.AUTOEQ_PREFIX
import com.spotifry.music.player.AutoEqBadFile
import com.spotifry.music.player.AutoEqEntry
import com.spotifry.music.player.AutoEqFit
import com.spotifry.music.player.fitAutoEq
import com.spotifry.music.player.searchAutoEq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_RESULTS = 30

private sealed interface Step {
    data object Idle : Step
    data class Fitting(val entry: AutoEqEntry) : Step
    data class Done(val entry: AutoEqEntry, val fit: AutoEqFit) : Step
    data class Failed(val text: String) : Step
}

/** Pick a headphone, and the ten bands are set to bend its measured sound toward the target curve. */
@Composable
internal fun AutoEqTab(settings: Settings, source: AutoEqSource, preset: String?) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    // what was found for which text, so "nothing found" isn't shown for a search still running
    var found by remember { mutableStateOf<Pair<String, List<AutoEqEntry>>?>(null) }
    val before by settings.eqBeforeSaved.collectAsStateWithLifecycle()
    val active = preset?.takeIf { it.startsWith(AUTOEQ_PREFIX) }?.removePrefix(AUTOEQ_PREFIX)

    LaunchedEffect(attempt) {
        listError = null
        try { list = source.index() } catch (e: Exception) {
            if (e is CancellationException) throw e
            listError = "Couldn't load the headphone list. Check your connection and try again."
        }
    }

    val requester = remember { BringIntoViewRequester() }
    // the field and the first few results, not the whole list: a list taller than the space above the keyboard would push the field off
    val topPx = with(LocalDensity.current) { 280.dp.toPx() }
    LaunchedEffect(found) {
        if (found?.second?.isNotEmpty() == true) { withFrameNanos { }; requester.bringIntoView(Rect(0f, 0f, 1f, topPx)) }
    }
    LaunchedEffect(query, list) {
        val all = list ?: return@LaunchedEffect
        found = query to withContext(Dispatchers.Default) { searchAutoEq(all, query) }
    }

    fun pick(entry: AutoEqEntry) {
        if (step is Step.Fitting) return
        // the keyboard would cover the result
        focus.clearFocus(); keyboard?.hide()
        // the download takes a while; if the equalizer is changed meanwhile, the result is dropped, not laid over the change
        val seenPreset = settings.eqPreset.value
        val seenLevels = settings.eqLevels.value
        val seenOn = settings.eqOn.value
        step = Step.Fitting(entry)
        scope.launch {
            step = try {
                val fit = withContext(Dispatchers.Default) { fitAutoEq(source.curve(entry)) }
                if (settings.applyEqCorrection(AUTOEQ_PREFIX + entry.name, fit.levelsMb, seenPreset, seenLevels, seenOn)) Step.Done(entry, fit) else Step.Idle
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Step.Failed(
                    if (e is AutoEqBadFile || e is IllegalArgumentException) "The measurement for ${entry.name} isn't available or can't be read. Try another result."
                    else "Couldn't get the measurement for ${entry.name}. Check your connection and try again.",
                )
            }
        }
    }

    Card {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), Arrangement.spacedBy(10.dp)) {
            if (active != null) {
                Text("Correcting for", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(active, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Chip(if (before) "Back to my curve" else "Turn off correction", false, Role.Button) { settings.restoreEqBefore() }
            }
            when (val s = step) {
                Step.Idle -> {}
                is Step.Fitting -> Text("Fitting ${s.entry.name}…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                is Step.Failed -> Text(s.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                is Step.Done -> if (active == s.entry.name) Text(resultText(s.fit), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            // with the keyboard up, the field and the first results are scrolled into the space above it
            Column(Modifier.bringIntoViewRequester(requester), Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Headphone model") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            val all = list
            when {
                listError != null -> {
                    Text(listError!!, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Chip("Try again", false, Role.Button) { attempt++ }
                }
                all == null -> Text("Loading the headphone list…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                query.isBlank() -> Text("Type part of the model name, like \"HD 600\" or \"WH-1000XM5\".", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                else -> {
                    // the old rows stay until the new ones arrive, so the page keeps its scroll position
                    val results = found?.second.orEmpty()
                    if (results.isEmpty() && found?.first == query) Text("No headphone with that name in the list.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    results.take(MAX_RESULTS).forEach { e ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, enabled = step !is Step.Fitting) { pick(e) }.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                            Text(e.sourceLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (results.size > MAX_RESULTS) Text("${results.size - MAX_RESULTS} more. Type more of the name to narrow it down.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            }
            Text(
                "Measurements by the AutoEq project, fitted to the ten bands here. Needs a connection to load the list and to pick a headphone.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Says what the fit got and what it cost, without claiming more than ten bands can do. */
internal fun resultText(fit: AutoEqFit): String {
    fun db(x: Double) = "%.1f".format(java.util.Locale.US, x)
    val quieter = if (fit.peakBoostDb >= 1) " The sound is turned down by ${db(fit.peakBoostDb)} dB overall so the boost can't clip, so it will play quieter." else ""
    return "Done. Typical gap to the target: ${db(fit.errorBeforeDb)} dB before, ${db(fit.errorAfterDb)} dB now. Ten bands can't follow narrow dips.$quieter"
}
