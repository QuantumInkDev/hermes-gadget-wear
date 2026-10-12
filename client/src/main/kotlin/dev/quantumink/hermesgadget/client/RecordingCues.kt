package dev.quantumink.hermesgadget.client

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

enum class Cue { START, FINISH, CANCEL }

/** Capture-worker feedback: complete before PCM forwarding, and after recorder release. */
class RecordingCues(private val context: Context) {
    @Suppress("DEPRECATION")
    @Synchronized
    fun play(cue: Cue) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java)
            val effect = VibrationEffect.createOneShot(
                if (cue ==
                    Cue.START
                ) {
                    20
                } else {
                    35
                },
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
                )
            } else {
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(
                        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    ).build()
                )
            }
        }
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val interruptionFilter = notifications.currentInterruptionFilter
            val normalRinger = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
            if (!CuePreferences.enabled.value || !normalRinger ||
                interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
                audio.isStreamMute(AudioManager.STREAM_NOTIFICATION) ||
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0
            ) {
                return@runCatching
            }
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
            try {
                val kind = when (cue) {
                    Cue.START -> ToneGenerator.TONE_PROP_BEEP
                    Cue.FINISH -> ToneGenerator.TONE_PROP_ACK
                    Cue.CANCEL -> ToneGenerator.TONE_PROP_NACK
                }
                if (tone.startTone(kind, 75)) Thread.sleep(110)
            } finally {
                tone.stopTone()
                tone.release()
            }
        }
    }
}
