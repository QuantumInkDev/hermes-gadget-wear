package dev.quantumink.hermesgadget.client

import android.media.AudioDeviceInfo

/** Observed response output only; no device identity or preferred-route claim. */
enum class PlaybackOutput { UNKNOWN, HEADPHONES, SPEAKER, OTHER }

internal fun playbackOutputForDeviceType(type: Int?, api: Int): PlaybackOutput = when {
    type == null -> PlaybackOutput.UNKNOWN
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> PlaybackOutput.SPEAKER
    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_HEARING_AID ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        (api >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) -> PlaybackOutput.HEADPHONES
    else -> PlaybackOutput.OTHER
}
