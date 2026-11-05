package com.musicremover.app

import android.app.Application
import com.musicremover.app.data.ErrorReport

/** Installs the crash recorder as early as possible, so crashes in workers and services are kept too. */
class MuremApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorReport.install(this)
    }
}
