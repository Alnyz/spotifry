package com.spotifry.music.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotifry.music.data.BackupError
import com.spotifry.music.data.BackupFile
import com.spotifry.music.data.Backups
import com.spotifry.music.data.backupFileName
import com.spotifry.music.data.readBackup
import com.spotifry.music.data.summary
import com.spotifry.music.data.writeBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

sealed interface BackupStep {
    data object None : BackupStep
    data class Working(val text: String) : BackupStep
    data class Note(val title: String, val text: String) : BackupStep
    data class Confirm(val file: BackupFile) : BackupStep
}

/** Survives Android's file dialog and a rotation; the dialogs below only draw [step]. */
class BackupViewModel(
    private val backups: Backups,
    private val output: (Uri) -> OutputStream?,
    private val input: (Uri) -> InputStream?,
    private val restart: () -> Unit,
) : ViewModel() {
    private val _step = MutableStateFlow<BackupStep>(BackupStep.None)
    val step: StateFlow<BackupStep> = _step

    fun save(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _step.value = BackupStep.Working("Saving your backup…")
        _step.value = try {
            val backup = backups.build()
            (output(uri) ?: throw java.io.IOException("no file")).use { writeBackup(backup, it) }
            BackupStep.Note("Backup saved", "Saved ${backup.summary()}. It holds your listening history, so keep it somewhere private.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            BackupStep.Note("Couldn't save the backup", "Check that there's room on the phone, then try again.")
        }
    }

    fun read(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _step.value = BackupStep.Working("Reading the backup…")
        _step.value = try {
            BackupStep.Confirm((input(uri) ?: throw BackupError(CANT_OPEN)).use(::readBackup))
        } catch (e: CancellationException) { throw e } catch (e: BackupError) {
            BackupStep.Note("Couldn't restore", e.message.orEmpty())
        } catch (e: Exception) {
            BackupStep.Note("Couldn't restore", CANT_OPEN)
        }
    }

    fun restore(file: BackupFile) = viewModelScope.launch(Dispatchers.IO) {
        _step.value = BackupStep.Working("Restoring…")
        try {
            backups.restore(file)
            restart()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _step.value = BackupStep.Note("Couldn't restore", "Something went wrong, so nothing was added. Try again.")
        }
    }

    fun dismiss() { _step.value = BackupStep.None }

    private companion object { const val CANT_OPEN = "Couldn't open that file. Nothing was changed." }
}

class BackupActions(val save: () -> Unit, val restore: () -> Unit)

/** Android's two file dialogs, and the messages around them. Call once; the actions work from any screen. */
@Composable
fun rememberBackupActions(vm: BackupViewModel): BackupActions {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::save) }
    // some file apps call a zip a plain binary file
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::read) }
    val step by vm.step.collectAsStateWithLifecycle()
    when (val s = step) {
        BackupStep.None -> {}
        is BackupStep.Working -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text(s.text) })
        is BackupStep.Note -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text(s.title) }, text = { Text(s.text) },
            confirmButton = { TextButton(vm::dismiss) { Text("OK") } },
        )
        is BackupStep.Confirm -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Restore this backup?") },
            text = { Text("${s.file.summary()}.\n\nThese join what's on this phone; nothing newer is overwritten. Your settings are replaced by the ones in the backup. The app restarts, so music stops.") },
            confirmButton = { TextButton({ vm.restore(s.file) }) { Text("Restore") } },
            dismissButton = { TextButton(vm::dismiss) { Text("Cancel") } },
        )
    }
    return remember(create, open) {
        BackupActions({ create.launch(backupFileName()) }, { open.launch(arrayOf("application/zip", "application/octet-stream")) })
    }
}
