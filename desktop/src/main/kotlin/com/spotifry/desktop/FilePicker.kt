package com.spotifry.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Asks for a file with the desktop's own dialog: KDE's (kdialog) or GNOME's (zenity) when installed, else Java's. Returns
 * null when the dialog is cancelled. Blocks until it closes, so it runs off the window's thread.
 */
object FilePicker {
    private fun has(tool: String) = runCatching { ProcessBuilder("which", tool).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)

    private fun run(vararg cmd: String): File? = runCatching {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) File(out) else null
    }.getOrNull()

    suspend fun save(title: String, defaultName: String): File? = withContext(Dispatchers.IO) {
        val start = File(System.getProperty("user.home"), defaultName).path
        when {
            has("kdialog") -> run("kdialog", "--title", title, "--getsavefilename", start, "*.zip|Spotifry backup (*.zip)")
            has("zenity") -> run("zenity", "--file-selection", "--save", "--confirm-overwrite", "--title=$title", "--filename=$start")
            else -> awt(title, FileDialog.SAVE, defaultName)
        }
    }

    suspend fun open(title: String): File? = withContext(Dispatchers.IO) {
        val start = System.getProperty("user.home") + "/"
        when {
            has("kdialog") -> run("kdialog", "--title", title, "--getopenfilename", start, "*.zip|Spotifry backup (*.zip)")
            has("zenity") -> run("zenity", "--file-selection", "--title=$title", "--filename=$start", "--file-filter=*.zip")
            else -> awt(title, FileDialog.LOAD, null)
        }
    }

    /** A folder, starting in [start]. */
    suspend fun folder(title: String, start: File): File? = withContext(Dispatchers.IO) {
        when {
            has("kdialog") -> run("kdialog", "--title", title, "--getexistingdirectory", start.path)
            has("zenity") -> run("zenity", "--file-selection", "--directory", "--title=$title", "--filename=${start.path}/")
            else -> javax.swing.JFileChooser(start).run {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = title
                if (showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selectedFile else null
            }
        }?.takeIf { it.isDirectory }
    }

    /** A picture file, starting in the Pictures folder. */
    suspend fun picture(title: String): File? = withContext(Dispatchers.IO) {
        val start = picturesDir().path + "/"
        when {
            has("kdialog") -> run("kdialog", "--title", title, "--getopenfilename", start, "*.jpg *.jpeg *.png *.webp|Pictures")
            has("zenity") -> run("zenity", "--file-selection", "--title=$title", "--filename=$start", "--file-filter=Pictures | *.jpg *.jpeg *.png *.webp")
            else -> awt(title, FileDialog.LOAD, null)
        }?.takeIf { it.isFile }
    }

    private fun awt(title: String, mode: Int, name: String?): File? {
        val d = FileDialog(null as Frame?, title, mode)
        if (name != null) d.file = name
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }
}
