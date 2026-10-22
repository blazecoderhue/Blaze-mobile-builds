package com.blaze.assistant.device

import android.media.AudioManager
import android.media.ToneGenerator
import com.blaze.assistant.agent.Settings

/** Two tiny cues: a rising blip when Blaze wakes, a soft double tick when a task is done. */
object Sounds {
    private fun play(vararg tones: Pair<Int, Int>) {
        if (!Settings.bool("sounds", true)) return
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
                for ((tone, ms) in tones) { tg.startTone(tone, ms); Thread.sleep(ms + 40L) }
                tg.release()
            } catch (_: Throwable) { }
        }.start()
    }
    fun wake() = play(ToneGenerator.TONE_PROP_BEEP to 60, ToneGenerator.TONE_PROP_ACK to 90)
    fun done() = play(ToneGenerator.TONE_PROP_PROMPT to 70)
    /** v0.41.0: a very short, quiet tick while a voice turn works (instead of reading the work steps aloud). */
    fun working() = play(ToneGenerator.TONE_PROP_ACK to 35)
}
