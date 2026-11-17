package com.deskwoot.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Looper
import com.deskwoot.sdk.engine.ChatSession
import com.deskwoot.sdk.ui.ScreenHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What `Deskwoot.setup` costs the main thread, as the README says: the real
 * session is made, and no folder, file, preference, package manager or
 * system service is touched on the main thread; the visitor's store is read
 * on a background thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupCostTest {
    /** Notes which thread asks the app's context for disk or system work; the background store read waits for [release]. */
    private class Recording(base: Context) : ContextWrapper(base) {
        val onMain = CopyOnWriteArrayList<String>()
        val elsewhere = CopyOnWriteArrayList<String>()
        val release = CountDownLatch(1)

        private fun note(what: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) onMain += what else elsewhere += what
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File {
            note("noBackupFilesDir")
            // The store read stops here, so the session never reaches the network in this test.
            if (Looper.myLooper() != Looper.getMainLooper()) release.await(5, TimeUnit.SECONDS)
            return super.getNoBackupFilesDir()
        }

        override fun getFilesDir(): File = super.getFilesDir().also { note("filesDir") }

        override fun getCacheDir(): File = super.getCacheDir().also { note("cacheDir") }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode).also { note("preferences") }

        override fun getPackageManager(): PackageManager = super.getPackageManager().also { note("packageManager") }

        override fun getSystemService(name: String): Any? = super.getSystemService(name).also { note("service $name") }
    }

    private val recording = Recording(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        Deskwoot.currentSession?.stop()
        recording.release.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        ScreenHarness.install(null)
    }

    @Test
    fun `setup touches no disk and no system service on the main thread`() {
        Deskwoot.makeSession = { context, token, language -> ChatSession.create(context, token, language) }
        Deskwoot.setup(recording, "wt_setup_cost")
        assertTrue("the session exists right after setup", Deskwoot.currentSession != null)
        val deadline = System.currentTimeMillis() + 5_000
        while ("noBackupFilesDir" !in recording.elsewhere && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("the visitor's store is read on a background thread", "noBackupFilesDir" in recording.elsewhere)
        assertEquals(emptyList<String>(), recording.onMain.toList())
    }
}
