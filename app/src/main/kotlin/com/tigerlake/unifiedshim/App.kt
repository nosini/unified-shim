package com.tigerlake.unifiedshim

import android.app.Application
import com.tigerlake.unifiedshim.common.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }
}
