package com.spotifry.desktop.bot

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Windows has no /dev/stdout for mpv to write its sound to, so Spotifry opens a named pipe and mpv writes into it as if
 * it were a file. The pipe's small buffer keeps mpv at the pace the sound is taken, as on Linux and macOS.
 */
class WindowsPipe : AutoCloseable {
    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun CreateNamedPipeW(name: CharArray, openMode: Int, pipeMode: Int, maxInstances: Int, outBuf: Int, inBuf: Int, timeout: Int, security: Pointer?): Pointer
        fun ConnectNamedPipe(pipe: Pointer, overlapped: Pointer?): Boolean
        fun ReadFile(file: Pointer, buffer: ByteArray, toRead: Int, read: IntByReference, overlapped: Pointer?): Boolean
        fun CloseHandle(handle: Pointer): Boolean
        fun GetLastError(): Int
    }

    private val k32 = Native.load("kernel32", Kernel32::class.java, W32APIOptions.UNICODE_OPTIONS)

    val path = "\\\\.\\pipe\\spotifry-pcm-${ProcessHandle.current().pid()}-${System.nanoTime()}"
    private val handle: Pointer = k32.CreateNamedPipeW((path + '\u0000').toCharArray(), PIPE_ACCESS_INBOUND, 0, 1, 0, BUFFER, 0, null)
        .also { check(Pointer.nativeValue(it) != INVALID_HANDLE) { "couldn't open a pipe for the sound (error ${k32.GetLastError()})" } }
    /** Opens when mpv connects; reads before that wait for it. */
    private val connected = CountDownLatch(1)
    @Volatile private var closed = false

    init {
        Thread({
            // "already connected" (535) means mpv was quicker than this call: that is fine too
            if (!k32.ConnectNamedPipe(handle, null) && k32.GetLastError() != ERROR_PIPE_CONNECTED) closed = true
            connected.countDown()
        }, "pcm-pipe-connect").apply { isDaemon = true; start() }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            connected.await()
            if (closed || len == 0) return if (len == 0) 0 else -1
            val tmp = if (off == 0) b else ByteArray(len)
            val got = IntByReference()
            // mpv closing its end (broken pipe) is the end of the sound
            if (!k32.ReadFile(handle, tmp, len, got, null) || got.value == 0) return -1
            if (tmp !== b) System.arraycopy(tmp, 0, b, off, got.value)
            return got.value
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        connected.countDown()
        k32.CloseHandle(handle)
    }

    private companion object {
        const val PIPE_ACCESS_INBOUND = 0x1
        const val ERROR_PIPE_CONNECTED = 535
        const val INVALID_HANDLE = -1L
        /** About 80 ms of sound: small, so mpv runs barely ahead of what Discord gets. */
        const val BUFFER = 16 * 1024
    }
}
