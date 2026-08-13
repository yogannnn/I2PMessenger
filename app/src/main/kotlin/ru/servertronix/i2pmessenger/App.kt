package ru.servertronix.i2pmessenger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        I2PManager.init(this)
    }
}