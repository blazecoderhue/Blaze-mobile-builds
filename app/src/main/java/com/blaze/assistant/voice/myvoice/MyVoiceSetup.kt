package com.blaze.assistant.voice.myvoice

/**
 * The My voice setup steps as pure state (MyVoiceSetupTest): whether step 2 is done, whether "Try it" can run, and
 * the plain line under step 3 that says exactly what is still missing.
 *
 * v0.41.0: step 3 used to say "Download the model and record first" whatever was missing, even with the model
 * downloaded and a recording on screen (the recording had been judged too short). It also forgot a good recording
 * saved on the phone (candidate.wav) when Settings was closed before "Use this voice".
 */
object MyVoiceSetup {
    data class Inputs(
        val packReady: Boolean,
        val downloading: Boolean = false,
        val recording: Boolean = false,
        /** The verdict of the recording shown on screen, or null when there is none this visit. */
        val recorded: AudioClip.Verdict? = null,
        /** A good recording saved earlier that hasn't been confirmed with "Use this voice" yet. */
        val candidateSaved: Boolean = false,
        /** The voice in use (reference.wav). */
        val hasVoice: Boolean = false,
    )

    data class Step3(val canTest: Boolean, val hint: String)

    /** A usable recording exists: a good one on screen, or (nothing new on screen) a saved one. */
    fun haveRecording(i: Inputs): Boolean = when {
        i.recording -> false
        i.recorded != null -> i.recorded == AudioClip.Verdict.OK
        else -> i.candidateSaved || i.hasVoice
    }

    fun step2Done(i: Inputs): Boolean = haveRecording(i)

    fun step3(i: Inputs): Step3 {
        val rec = haveRecording(i)
        if (i.packReady && rec) return Step3(true, "Blaze will say a test sentence in your voice.")
        val recHint = when {
            i.recording -> "Finish recording first (step 2)."
            i.recorded != null && i.recorded != AudioClip.Verdict.OK -> "Record again first (step 2). " + shortReason(i.recorded)
            else -> null
        }
        val hint = when {
            !i.packReady && !rec && recHint == null -> "Download the voice model and record your voice first."
            !i.packReady && i.downloading -> "Waiting for the voice model to finish downloading." + (recHint?.let { " $it" } ?: "")
            !i.packReady -> "Download the voice model first (step 1)." + (recHint?.let { " $it" } ?: "")
            recHint != null -> recHint
            else -> "Record your voice first (step 2)."
        }
        return Step3(false, hint)
    }

    private fun shortReason(v: AudioClip.Verdict): String = when (v) {
        AudioClip.Verdict.TOO_SHORT -> "The last one was too short to use."
        AudioClip.Verdict.TOO_QUIET -> "The last one was too quiet."
        AudioClip.Verdict.TOO_LOUD -> "The last one was distorted."
        AudioClip.Verdict.TOO_NOISY -> "The last one had too much background noise."
        AudioClip.Verdict.NOTHING_HEARD -> "I couldn't hear the last one."
        AudioClip.Verdict.OK -> ""
    }
}
