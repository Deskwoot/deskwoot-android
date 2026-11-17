package com.deskwoot.example

import android.app.Application
import android.os.StrictMode
import com.deskwoot.sdk.Deskwoot

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // The SDK must do no disk or network work on the main thread and leak nothing: StrictMode logs any of it.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        Deskwoot.logLevel = Deskwoot.LogLevel.DEBUG
        // Setup costs nothing on the main thread; call it as early as you like.
        Deskwoot.setup(this, BuildConfig.WIDGET_TOKEN)
        // The signed in user right after setup, at every start: Android can start this process for a tap on a
        // notification without running any Activity, and the tapped conversation of a signed in user opens
        // only once the app named them.
        if (TestUser.identifiesAtStart(this)) Deskwoot.identify(TestUser.deskwootUser())
    }
}
