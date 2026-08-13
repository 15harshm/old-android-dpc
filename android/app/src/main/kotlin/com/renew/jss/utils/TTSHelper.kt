package com.renew.jss.utils

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSHelper private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: TTSHelper? = null

        fun getInstance(): TTSHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TTSHelper().also { INSTANCE = it }
            }
        }

        private const val TAG = "TTSHelper"

        /** The English lead-in spoken before the Hindi reminder. */
        const val ENGLISH_EMI_REMINDER = "EMI payment reminder"

        /** The Hindi EMI reminder spoken after the English lead-in. */
        const val HINDI_EMI_REMINDER =
            "प्रिय ग्राहक, मैं आपसे अनुरोध करती हूँ कि कृपया अपनी किस्त समय पर जमा करें। देर से भुगतान करने पर पेनल्टी लग सकती है। धन्यवाद।"

        private const val UTTERANCE_EN = "EMI_REMINDER_EN"
        private const val UTTERANCE_HI = "EMI_REMINDER_HI"
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    fun initializeTTS(context: Context, onInitComplete: ((Boolean) -> Unit)? = null) {
        if (isInitialized) {
            onInitComplete?.invoke(true)
            return
        }

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
                onInitComplete?.invoke(true)
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                onInitComplete?.invoke(false)
            }
        }
    }

    /**
     * Speaks the EMI reminder in TWO parts, back-to-back, in a (best-effort) female voice:
     *   1) [englishText] in English (default "EMI payment reminder")
     *   2) the fixed Hindi sentence [HINDI_EMI_REMINDER]
     *
     * The Hindi part is started from the English part's completion callback so the engine's
     * language can be switched cleanly between them (a single QUEUE_FLUSH utterance can only
     * use one language). [onCompleted] fires after the Hindi part finishes.
     */
    fun speakEmiReminderBilingual(
        context: Context,
        englishText: String = ENGLISH_EMI_REMINDER,
        onCompleted: (() -> Unit)? = null
    ) {
        if (!isInitialized) {
            initializeTTS(context) { success ->
                if (success) {
                    speakEmiReminderBilingual(context, englishText, onCompleted)
                } else {
                    Log.e(TAG, "Cannot speak reminder - TTS not initialized")
                    onCompleted?.invoke()
                }
            }
            return
        }

        val tts = textToSpeech ?: run {
            onCompleted?.invoke()
            return
        }

        // Slightly slower than normal for clarity.
        tts.setSpeechRate(0.85f)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                when (utteranceId) {
                    UTTERANCE_EN -> speakHindiPart(tts, onCompleted)
                    UTTERANCE_HI -> {
                        Log.d(TAG, "Bilingual EMI reminder completed")
                        onCompleted?.invoke()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                when (utteranceId) {
                    // If English fails, still try to deliver the Hindi reminder.
                    UTTERANCE_EN -> speakHindiPart(tts, onCompleted)
                    else -> onCompleted?.invoke()
                }
            }
        })

        // Part 1 — English lead-in.
        setSpokenLanguage(tts, "en-US")
        applyFemaleVoice(tts, Locale.US)
        val enParams = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_EN)
        }
        if (tts.speak(englishText, TextToSpeech.QUEUE_FLUSH, enParams, UTTERANCE_EN) == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to speak English part; attempting Hindi directly")
            speakHindiPart(tts, onCompleted)
        }
    }

    /** Part 2 — the fixed Hindi reminder. Called after the English part completes. */
    private fun speakHindiPart(tts: TextToSpeech, onCompleted: (() -> Unit)?) {
        setSpokenLanguage(tts, "hi-IN")
        applyFemaleVoice(tts, Locale.forLanguageTag("hi-IN"))
        val hiParams = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_HI)
        }
        if (tts.speak(HINDI_EMI_REMINDER, TextToSpeech.QUEUE_FLUSH, hiParams, UTTERANCE_HI) == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to speak Hindi part")
            onCompleted?.invoke()
        }
    }

    /** Sets the engine language, falling back to US English if the requested one is unavailable. */
    private fun setSpokenLanguage(tts: TextToSpeech, tag: String) {
        val locale = Locale.forLanguageTag(tag)
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not available: $tag (result=$result)")
            // For Hindi, there is no good Latin fallback — leave the engine default so the device
            // can still attempt it; for other tags fall back to English.
            if (!tag.startsWith("hi")) {
                tts.setLanguage(Locale.US)
            }
        }
    }

    /**
     * Best-effort female-voice selection. Android's TTS API exposes no official gender field, so we
     * look for a voice for the target language whose name explicitly marks it female. If none is
     * found we leave the engine default in place — for the common Google TTS engine the default
     * hi-IN / en-US voices are already female. Never throws.
     */
    private fun applyFemaleVoice(tts: TextToSpeech, locale: Locale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val voices = tts.voices ?: return
            val female = voices.firstOrNull { v ->
                !v.isNetworkConnectionRequired &&
                    v.locale.language == locale.language &&
                    v.name.lowercase(Locale.ROOT).let { it.contains("female") || it.contains("#f") }
            } ?: voices.firstOrNull { v ->
                v.locale.language == locale.language &&
                    v.name.lowercase(Locale.ROOT).let { it.contains("female") || it.contains("#f") }
            }
            if (female != null) {
                tts.voice = female
                Log.d(TAG, "Female voice selected for ${locale.language}: ${female.name}")
            } else {
                Log.d(TAG, "No explicit female voice for ${locale.language}; using engine default")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyFemaleVoice failed: ${e.message}")
        }
    }

    fun speakTTS(context: Context, message: String, language: String? = null, onCompleted: (() -> Unit)? = null) {
        if (!isInitialized) {
            initializeTTS(context) { success ->
                if (success) {
                    speakTTS(context, message, language, onCompleted)
                } else {
                    Log.e(TAG, "Cannot speak - TTS not initialized")
                    onCompleted?.invoke()
                }
            }
            return
        }

        val tts = textToSpeech ?: return

        // Set language
        val langCode = when {
            language == "hi" || language == "hi-IN" -> "hi-IN"
            language?.isNotEmpty() == true -> language
            else -> "en-US"
        }

        val locale = Locale.forLanguageTag(langCode.replace("-", "_"))
        val result = tts.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: $langCode, falling back to English")
            tts.setLanguage(Locale.US)
        }

        // Best-effort female voice for this language.
        applyFemaleVoice(tts, locale)

        // Set slower speech rate for better understandability (0.8 = 80% of normal speed)
        tts.setSpeechRate(0.8f)

        // Set utterance progress listener
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed: $utteranceId")
                onCompleted?.invoke()
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                onCompleted?.invoke()
            }
        })

        // Speak the message
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "EMI_REMINDER_ID")

        val speakResult = tts.speak(message, TextToSpeech.QUEUE_FLUSH, params, "EMI_REMINDER_ID")

        if (speakResult == TextToSpeech.ERROR) {
            Log.e(TAG, "Failed to speak message")
            onCompleted?.invoke()
        }
    }

    fun speakTTSEnglish(context: Context, message: String, onCompleted: (() -> Unit)? = null) {
        speakTTS(context, message, "en-US", onCompleted)
    }

    fun stopTTS() {
        textToSpeech?.stop()
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }
}
