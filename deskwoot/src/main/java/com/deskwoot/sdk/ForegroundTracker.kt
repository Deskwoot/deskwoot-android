package com.deskwoot.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle

/**
 * Tells the session when the app goes to the background and comes back,
 * so the poll and the realtime stream run only while the app is in front.
 * Keeps the identity hash of each started Activity, never
 * the Activity itself. A rotation (stop and start of the same screen) is
 * not reported.
 */
internal class ForegroundTracker(private val onChange: (Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val started = HashSet<Int>()

    /** A callback arrived: it knows better than the first guess of [isForegroundNow]. */
    @Volatile
    var heardAny: Boolean = false
        private set

    override fun onActivityStarted(activity: Activity) {
        heardAny = true
        started += System.identityHashCode(activity)
        onChange(true)
    }

    override fun onActivityStopped(activity: Activity) {
        heardAny = true
        started -= System.identityHashCode(activity)
        if (started.isEmpty() && !activity.isChangingConfigurations) onChange(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {
        /**
         * Whether the app shows something right now, for the state before the
         * first callback. Only IMPORTANCE_FOREGROUND counts: a foreground
         * service (125) or, below API 26, a perceptible process (130) is a
         * music or navigation app in the background, which must not poll or
         * hold a stream.
         */
        fun isForegroundNow(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return countsAsForeground(info.importance)
        }

        fun countsAsForeground(importance: Int): Boolean =
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
