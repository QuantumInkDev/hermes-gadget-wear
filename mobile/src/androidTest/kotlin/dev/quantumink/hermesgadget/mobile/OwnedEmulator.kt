package dev.quantumink.hermesgadget.mobile

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** A model name alone is neither stable across API levels nor proof of an emulator. */
internal fun isAndroidEmulator(): Boolean {
    if (Build.HARDWARE !in setOf("ranchu", "goldfish")) return false
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("getprop ro.kernel.qemu")
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
        it.readText().trim() == "1"
    }
}
