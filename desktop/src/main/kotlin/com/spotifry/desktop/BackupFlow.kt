package com.spotifry.desktop

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifry.music.data.BackupError
import com.spotifry.music.data.BackupFile
import com.spotifry.music.data.BackupPartlyApplied
import com.spotifry.music.data.backupFileName
import com.spotifry.music.data.readBackup
import com.spotifry.music.data.summary
import com.spotifry.music.data.writeBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupStep {
    data object None : BackupStep
    data class Working(val text: String) : BackupStep
    data class Note(val title: String, val text: String) : BackupStep
    data class Confirm(val file: BackupFile, val unusableSettings: Int = 0) : BackupStep
}

private const val CANT_OPEN = "Couldn't open that file. Nothing was changed."

/**
 * Save a backup, or read one and restore it, the same steps and words as the phone. [restart] runs after a restore,
 * so the window opens again with what was restored.
 */
class BackupFlow(private val lib: Library, private val scope: CoroutineScope, private val restart: () -> Unit) {
    var step by mutableStateOf<BackupStep>(BackupStep.None)
        private set

    fun dismiss() { step = BackupStep.None }

    fun save() { scope.launch {
        val file = FilePicker.save("Save backup", backupFileName()) ?: return@launch
        step = BackupStep.Working("Saving your backup…")
        step = try {
            val backup = lib.backups.build()
            withContext(Dispatchers.IO) { file.outputStream().use { writeBackup(backup, it) } }
            BackupStep.Note("Backup saved", "Saved ${backup.summary()}. It holds your listening history, so keep it somewhere private.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            BackupStep.Note("Couldn't save the backup", "Check that there's room on this computer, then try again.")
        }
    } }

    fun choose() { scope.launch {
        val file = FilePicker.open("Restore backup") ?: return@launch
        step = BackupStep.Working("Reading the backup…")
        step = try {
            val backup = withContext(Dispatchers.IO) { file.inputStream().use(::readBackup) }
            BackupStep.Confirm(backup, lib.backups.unusableSettings(backup).size)
        } catch (e: CancellationException) { throw e } catch (e: BackupError) { BackupStep.Note("Couldn't restore", e.message.orEmpty()) } catch (e: Exception) { BackupStep.Note("Couldn't restore", CANT_OPEN) }
    } }

    fun restore(file: BackupFile) { scope.launch {
        step = BackupStep.Working("Restoring…")
        try {
            lib.backups.restore(file)
            restart()
        } catch (e: CancellationException) { throw e } catch (e: BackupPartlyApplied) {
            step = BackupStep.Note("Restored only in part", "Your playlists, songs and history were added, but your settings or trims may not have been. Restoring the same file again is safe.")
        } catch (e: Exception) {
            step = BackupStep.Note("Couldn't restore", "Something went wrong, so nothing was added. Try again.")
        }
    } }
}

@Composable
fun BackupDialogs(flow: BackupFlow) {
    when (val s = flow.step) {
        BackupStep.None -> Unit
        is BackupStep.Working -> AlertDialog(
            onDismissRequest = {}, containerColor = Palette.surface, title = { Text(s.text, fontFamily = Display, fontSize = 22.sp) },
            text = { CircularProgressIndicator(color = Palette.accent, modifier = androidx.compose.ui.Modifier.padding(8.dp)) }, confirmButton = {},
        )
        is BackupStep.Note -> AlertDialog(
            onDismissRequest = flow::dismiss, containerColor = Palette.surface, title = { Text(s.title, fontFamily = Display, fontSize = 22.sp) }, text = { Text(s.text, color = Palette.ink2) },
            confirmButton = { TextButton(flow::dismiss) { Text("OK", color = Palette.accent) } },
        )
        is BackupStep.Confirm -> {
            val left = s.unusableSettings
            val skipped = if (left == 0) "" else " $left ${if (left == 1) "setting" else "settings"} in the file can't be used here and ${if (left == 1) "stays" else "stay"} as ${if (left == 1) "it is" else "they are"}."
            AlertDialog(
                onDismissRequest = flow::dismiss, containerColor = Palette.surface, title = { Text("Restore this backup?", fontFamily = Display, fontSize = 22.sp) },
                text = { Text("${s.file.summary()}.\n\nThese join what's on this computer; nothing newer is overwritten. Your settings are replaced by the ones in the backup.$skipped The app restarts, so music stops.", color = Palette.ink2) },
                confirmButton = { TextButton({ flow.restore(s.file) }) { Text("Restore", color = Palette.accent) } },
                dismissButton = { TextButton(flow::dismiss) { Text("Cancel", color = Palette.ink2) } },
            )
        }
    }
}
