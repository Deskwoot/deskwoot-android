package com.deskwoot.sdk

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import java.io.File

/**
 * Whether this process is the app's default one. An app with more than one
 * process (a service of its own, a widget, a library's helper) runs
 * `Application.onCreate`, and so `Deskwoot.setup`, in each of them; the SDK
 * works only in the default process until another one shows the messenger
 * or handles a push, because the processes share its files with no lock
 * between them.
 */
internal object AppProcess {
    /** The process's name as the tests set it; null asks the system. */
    @Volatile
    var nameForTests: String? = null

    private val systemName: String? by lazy { readName() }

    /** True when the name of this process is not known: the SDK then works as it always did. */
    fun isDefault(context: Context): Boolean {
        val name = nameForTests ?: systemName ?: return true
        val default = context.applicationInfo?.processName?.takeIf { it.isNotEmpty() } ?: context.packageName
        return name == default
    }

    private fun readName(): String? {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()?.takeIf { it.isNotEmpty() }
        // Before Android 9 the kernel's own line for this process: a read of procfs, no flash storage, so
        // StrictMode is told not to count it on the main thread.
        val policy = StrictMode.allowThreadDiskReads()
        return try {
            File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }
}
