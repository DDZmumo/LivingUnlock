package com.windowslockpin.companion

import android.app.Application
import com.windowslockpin.companion.bluetooth.BleUnlockScanManager
import com.windowslockpin.companion.core.model.SafeLogger

import android.app.Activity
import android.os.Bundle
import com.windowslockpin.companion.service.UnlockSessionManager

class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.windowslockpin.companion.time.AppTime.start(this)
        SafeLogger.i(TAG, "WindowsLockPin Companion application initialized")

        // Track foreground/background state so floating heads-up notifications
        // are only displayed when the user is outside the app.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumedActivities = 0

            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                UnlockSessionManager.isAppInForeground = true
                SafeLogger.d(TAG, "Activity resumed (active=$resumedActivities) -> isAppInForeground=true")
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities = maxOf(0, resumedActivities - 1)
                UnlockSessionManager.isAppInForeground = (resumedActivities > 0)
                SafeLogger.d(TAG, "Activity paused (active=$resumedActivities) -> isAppInForeground=${UnlockSessionManager.isAppInForeground}")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Start background BLE scan so the PC lock-screen beacon wakes us instantly,
        // even if the user has not opened the app. Works after reboot via BootReceiver.
        BleUnlockScanManager.startScan(this)
    }

    companion object {
        private const val TAG = "CompanionApp"
    }
}
