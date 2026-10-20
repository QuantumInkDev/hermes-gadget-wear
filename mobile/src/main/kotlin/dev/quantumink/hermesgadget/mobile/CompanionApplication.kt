package dev.quantumink.hermesgadget.mobile

import android.app.Application

/** Application ownership coalesces discovery without retaining an Activity or Service. */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.quantumink.hermesgadget.client.CuePreferences.initialize(this)
        // An interrupted configuration import is undone or finished before any normal write.
        SetupTransferGate.install {
            if (SetupJournalFile.present(java.io.File(filesDir, "setup-transfer"))) {
                SetupImport.recover(SetupTransferStores.get(this))
            }
        }
        Thread { runCatching { SetupTransferGate.ensureRecovered() } }.start()
    }
    val catalog: CatalogRepository by lazy { CatalogRepository(this) }
}
