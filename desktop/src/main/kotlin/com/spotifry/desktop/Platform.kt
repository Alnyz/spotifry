package com.spotifry.desktop

import java.io.File

/** Where the app runs, and the few things that differ between Linux and Windows. */
object Platform {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    val isLinux = os.contains("linux")

    /** Files that come inside the installed app (mpv and ffmpeg on Windows); null when the app runs from Gradle. */
    private val bundled: File? = System.getProperty("compose.application.resources.dir")?.let(::File)

    /** [name] as a command: the copy that came with the app when there is one, else whatever the PATH holds. */
    fun tool(name: String): String {
        val exe = if (isWindows) "$name.exe" else name
        return bundled?.resolve(exe)?.takeIf { it.isFile }?.path ?: name
    }

    /** The full path of [name] (bundled or on the PATH), or null when it isn't there. */
    fun find(name: String): String? {
        bundled?.resolve(if (isWindows) "$name.exe" else name)?.takeIf { it.isFile }?.let { return it.path }
        return runCatching {
            val p = ProcessBuilder(if (isWindows) "where" else "which", name).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.inputStream.bufferedReader().readLines().firstOrNull { it.isNotBlank() }?.trim().takeIf { p.waitFor() == 0 }
        }.getOrNull()
    }

    /** Where the app keeps its files: %APPDATA%\spotifry on Windows, $XDG_DATA_HOME/spotifry (else ~/.local/share/spotifry) elsewhere. */
    fun dataRoot(): File {
        val home = System.getProperty("user.home")
        return if (isWindows) File(System.getenv("APPDATA") ?: "$home/AppData/Roaming") else File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share")
    }

    /** The user's Music or Pictures folder: the desktop's own (Linux xdg-user-dir) or ~/Music, ~/Pictures. */
    fun userFolder(kind: String): File {
        val home = System.getProperty("user.home")
        if (isLinux) runCatching {
            val p = ProcessBuilder("xdg-user-dir", kind.uppercase()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() && it != home }?.let { return File(it) }
        }
        return File(home, kind)
    }
}
