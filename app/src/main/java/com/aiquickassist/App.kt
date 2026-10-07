package com.aiquickassist

import android.app.Application
import com.aiquickassist.data.HistoryStore
import com.aiquickassist.data.SecureStore
import com.aiquickassist.data.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        SecureStore.init(this)
        HistoryStore.init(this)
        com.aiquickassist.engine.GoogleEngine.init(this)
    }
}
