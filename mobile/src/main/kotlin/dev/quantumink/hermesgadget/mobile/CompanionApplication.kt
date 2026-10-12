package dev.quantumink.hermesgadget.mobile

import android.app.Application

/** Application ownership coalesces discovery without retaining an Activity or Service. */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.quantumink.hermesgadget.client.CuePreferences.initialize(this)
    }
    val catalog: CatalogRepository by lazy { CatalogRepository(this) }
}
