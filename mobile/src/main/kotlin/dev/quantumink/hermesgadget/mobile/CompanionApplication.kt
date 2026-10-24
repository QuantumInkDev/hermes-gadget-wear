package dev.quantumink.hermesgadget.mobile

import android.app.Application
import dev.quantumink.hermesgadget.client.CuePreferences

/** Application ownership coalesces discovery without retaining an Activity or Service. */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CuePreferences.initialize(this)
        // An interrupted configuration import is undone or finished before any normal write.
        SetupTransferGate.install {
            if (SetupJournalFile.present(java.io.File(filesDir, "setup-transfer"))) {
                SetupImport.recover(SetupTransferStores.get(this))
            }
        }
        Thread { runCatching { SetupTransferGate.ensureRecovered() } }.start()
    }
    internal val inbox: PhoneInboxOwner by lazy { PhoneInboxOwner(this) }
    val catalog: CatalogRepository by lazy { CatalogRepository(this) }
}
