// The idea of picking a headphone from AutoEq's list and fitting a correction to it is from Mochy Music (GPL-3.0), see licenses/Mochy-Music-NOTICE.txt.
// The measurements are from the AutoEq project (MIT, https://github.com/jaakkopasanen/AutoEq).

package com.spotifry.music.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifry.music.data.AutoEqSource
import com.spotifry.music.data.Settings
import com.spotifry.music.player.AutoEqEntry
import com.spotifry.music.player.AutoEqFit
import com.spotifry.music.player.fitAutoEq
import com.spotifry.music.player.searchAutoEq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts the name of a saved headphone correction, so the page can tell it from a preset or the user's own curve. */
internal const val AUTOEQ_PREFIX = "AutoEq: "
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
    var query by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    val before by settings.eqBeforeSaved.collectAsStateWithLifecycle()
    val active = preset?.takeIf { it.startsWith(AUTOEQ_PREFIX) }?.removePrefix(AUTOEQ_PREFIX)

    LaunchedEffect(attempt) {
        listError = null
        try { list = source.index() } catch (e: Exception) {
            if (e is CancellationException) throw e
            listError = "Couldn't load the headphone list. Check your connection and try again."
        }
    }

    fun pick(entry: AutoEqEntry) {
        step = Step.Fitting(entry)
        scope.launch {
            step = try {
                val fit = withContext(Dispatchers.Default) { fitAutoEq(source.curve(entry)) }
                // the user's own curve is kept once, so going back works even after trying several headphones
                if (active == null) settings.saveEqBefore()
                settings.setEq(AUTOEQ_PREFIX + entry.name, fit.levelsMb)
                settings.setEqOn(true)
                Step.Done(entry, fit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Step.Failed("Couldn't get the measurement for ${entry.name}. Check your connection and try again.")
            }
        }
    }

    Card {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), Arrangement.spacedBy(10.dp)) {
            if (active != null) {
                Text("Correcting for", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(active, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Chip(if (before) "Back to my curve" else "Turn off correction", false) { settings.restoreEqBefore() }
            }
            when (val s = step) {
                Step.Idle -> {}
                is Step.Fitting -> Text("Fitting ${s.entry.name}…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                is Step.Failed -> Text(s.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                is Step.Done -> Text(resultText(s.fit), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Headphone model") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            val all = list
            when {
                listError != null -> {
                    Text(listError!!, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Chip("Try again", false) { attempt++ }
                }
                all == null -> Text("Loading the headphone list…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                query.isBlank() -> Text("Type part of the model name, like \"HD 600\" or \"WH-1000XM5\".", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                else -> {
                    val found = searchAutoEq(all, query)
                    if (found.isEmpty()) Text("No headphone with that name in the list.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    found.take(MAX_RESULTS).forEach { e ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, enabled = step !is Step.Fitting) { pick(e) }.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                            Text(e.sourceLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (found.size > MAX_RESULTS) Text("${found.size - MAX_RESULTS} more. Type more of the name to narrow it down.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Text(
                "Measurements by the AutoEq project, fitted to the ten bands here. Needs a connection to load the list and each headphone once.",
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
