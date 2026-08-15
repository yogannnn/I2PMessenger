package ru.servertronix.i2pmessenger

import android.app.Application
import android.util.Log

/**
 * Application intentionally does NOT own the I2P/SAM lifecycle.
 *
 * The Foreground Service is the single owner of I2PManager and PresenceManager.
 * Activities may disappear at any time without tearing down the network stack.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("App", "Application started; I2P lifecycle is owned by I2PService")
    }
}
