package dev.quantumink.hermesgadget.mobile

import android.app.Application

/** Application ownership coalesces discovery without retaining an Activity or Service. */
class CompanionApplication : Application() {
    val catalog: CatalogRepository by lazy { CatalogRepository(this) }
}
