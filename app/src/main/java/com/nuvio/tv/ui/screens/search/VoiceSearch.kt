package com.nuvio.tv.ui.screens.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R

internal class VoiceSearch(private val context: Context, private val language: String? = null) {
    val available = SpeechRecognizer.isRecognitionAvailable(context)
    private var recognizer: SpeechRecognizer? by mutableStateOf(null)
    val listening: Boolean get() = recognizer != null
    var rmsLevel by mutableFloatStateOf(0f)
        private set

    fun stop() {
        val session = recognizer ?: return
        recognizer = null
        rmsLevel = 0f
        session.destroy()
    }

    fun start(onResult: (String) -> Unit) {
        if (listening) return
        try {
            val session = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = session
            session.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onEndOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (recognizer === session) rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== session) return
                    stop()
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (phrase.isNotEmpty()) onResult(phrase)
                    else Toast.makeText(context, R.string.search_voice_no_speech, Toast.LENGTH_SHORT).show()
                }

                override fun onError(error: Int) {
                    if (recognizer !== session) return
                    stop()
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.search_voice_no_speech
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.search_voice_mic_permission
                        else -> R.string.search_voice_failed
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            })
            session.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (language != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    if (Build.VERSION.SDK_INT >= 34) {
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                        putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                            arrayListOf(language, "en-US"))
                    }
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            })
        } catch (_: RuntimeException) {
            stop()
            Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun rememberVoiceSearch(language: String? = null, onResult: (String) -> Unit): Pair<VoiceSearch, () -> Unit> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val voice = remember(context, language) { VoiceSearch(context, language) }
    val latestResult by rememberUpdatedState(onResult)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.start { latestResult(it) }
        else if (!granted) Toast.makeText(context, R.string.search_voice_mic_permission, Toast.LENGTH_SHORT).show()
    }
    DisposableEffect(voice, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            voice.stop()
        }
    }
    return voice to {
        when {
            voice.listening -> voice.stop()
            !voice.available -> Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                voice.start { latestResult(it) }
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
