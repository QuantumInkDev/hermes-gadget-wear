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
import android.provider.Settings

enum class Cue { START, FINISH, CANCEL }

enum class WatchTick { PRIMITIVE, AMPLITUDE, EFFECT, DEFAULT }

object WatchRecordingHaptic {
    const val SCALE = 0.15f
    const val PULSE_MILLIS = 8L
    const val AMPLITUDE = 32
    const val FALLBACK_MILLIS = 6L
    fun select(primitive: Boolean, amplitude: Boolean, effect: Boolean): WatchTick = when {
        primitive -> WatchTick.PRIMITIVE
        amplitude -> WatchTick.AMPLITUDE
        effect -> WatchTick.EFFECT
        else -> WatchTick.DEFAULT
    }
}

/** Capture-worker feedback: complete before PCM forwarding, and after recorder release. */
class RecordingCues(private val context: Context, private val watchLightHaptics: Boolean = false) {
    @Suppress("DEPRECATION")
    @Synchronized
    fun play(cue: Cue) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (watchLightHaptics && Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1
                ) == 0
            ) {
                return@runCatching
            }
            val effect = if (watchLightHaptics && cue != Cue.CANCEL) {
                val primitive =
                    Build.VERSION.SDK_INT >= 30 &&
                        vibrator.arePrimitivesSupported(
                            VibrationEffect.Composition.PRIMITIVE_TICK
                        ).all {
                            it
                        }
                val tick =
                    Build.VERSION.SDK_INT >= 30 &&
                        vibrator.areEffectsSupported(VibrationEffect.EFFECT_TICK).all {
                            it ==
                                Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                        }
                when (
                    WatchRecordingHaptic.select(
                        primitive,
                        vibrator.hasAmplitudeControl(),
                        tick
                    )
                ) {
                    WatchTick.PRIMITIVE -> if (Build.VERSION.SDK_INT >= 30) {
                        VibrationEffect.startComposition().addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_TICK,
                            WatchRecordingHaptic.SCALE
                        ).compose()
                    } else {
                        VibrationEffect.createOneShot(
                            WatchRecordingHaptic.FALLBACK_MILLIS,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    }
                    WatchTick.AMPLITUDE -> VibrationEffect.createOneShot(
                        WatchRecordingHaptic.PULSE_MILLIS,
                        WatchRecordingHaptic.AMPLITUDE
                    )
                    WatchTick.EFFECT -> VibrationEffect.createPredefined(
                        VibrationEffect.EFFECT_TICK
                    )
                    WatchTick.DEFAULT -> VibrationEffect.createOneShot(
                        WatchRecordingHaptic.FALLBACK_MILLIS,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                }
            } else {
                VibrationEffect.createOneShot(
                    if (cue ==
                        Cue.START
                    ) {
                        20
                    } else {
                        35
                    },
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            }
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
