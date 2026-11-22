package com.sen0413.ultimatewidget

import android.app.Application
import com.sen0413.ultimatewidget.hub.HubController

class UltimateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HubController.init(this)
    }
}
