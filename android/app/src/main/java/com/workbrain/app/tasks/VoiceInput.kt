package com.workbrain.app.tasks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.workbrain.app.record.RecStatus
import com.workbrain.app.record.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 빠른 입력 '말로 넣기'(DEC-89): Android 음성 인식을 화면 밖 object가 들고 있는다.
 * 접기·펼치기로 입력 줄 화면이 다시 그려져도 듣던 것이 끊기지 않고, 다 들은 글은 그 입력 줄(key)이 받아 이어 붙인다.
 * 기기 안 인식을 먼저 쓰고, 없거나 한국어를 못 하면 휴대폰 기본 인식으로 넘긴다(사용자 선택 11/11).
 * WB 서버로 보내지 않고 AI도 부르지 않는다(비용 0원). 개발 기록에는 오류 코드만 남기고 말한 내용은 남기지 않는다.
 * 모든 호출은 화면(메인 스레드)에서 한다(SpeechRecognizer 규칙)
 */
object VoiceInput {
    private const val TAG = "WorkBrainVoice"
    private const val PREFS = "voice_input"
    private const val KEY_ONLINE_NOTED = "online_noted"
    // 안내는 잠깐 보이고 사라진다. 남겨 두면 다른 탭에 갔다 와도 빨간 카드가 계속 떠 있다(11/11 실기기 확인)
    private const val MESSAGE_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())

    /** 입력 줄에 보일 안내. isError면 빨간 글씨, 아니면 회색 안내 */
    data class Message(val text: String, val isError: Boolean)

    /** key: 지금 말로 넣는 입력 줄(today·all·place:…). partial: 듣는 중 중간 글 */
    data class State(val key: String? = null, val listening: Boolean = false, val partial: String = "", val message: Message? = null)

    /** 다 들은 글. 받는 입력 줄이 이어 붙인 뒤 consume으로 비운다. seq는 같은 글을 두 번 말해도 새 값으로 보이게 */
    data class Heard(val key: String, val text: String, val seq: Long)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _heard = MutableStateFlow<Heard?>(null)
    val heard: StateFlow<Heard?> = _heard
    private var seq = 0L
    private var recognizer: SpeechRecognizer? = null

    /** 듣기 시작. 마이크 권한은 부르는 쪽(마이크 버튼)이 먼저 받아 둔다 */
    fun start(context: Context, key: String) {
        val app = context.applicationContext
        release()
        // 녹음과 마이크를 같이 쓸 수 없다
        if (RecordingService.state.value.status != RecStatus.IDLE) {
            fail(key, "녹음 중에는 말로 넣을 수 없어요. 녹음을 마친 뒤 눌러 주세요.")
            return
        }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(app)) {
            fail(key, "이 휴대폰에서는 음성 인식을 쓸 수 없어요. 글로 적어 주세요.")
            return
        }
        listen(app, key, onDevice)
    }

    /** 다시 누르면 멈춘다. 그때까지 들은 것으로 마무리한다 */
    fun stop() {
        recognizer?.stopListening()
    }

    /** 녹음 메모로 넘어갈 때처럼 들은 것을 버리고 끝낸다 */
    fun cancel() {
        release()
        _state.value = State()
    }

    fun consume(heard: Heard) {
        if (_heard.value == heard) _heard.value = null
    }

    /** 그 입력 줄에 남은 안내를 지운다(다시 적기 시작했을 때) */
    fun clearMessage(key: String) {
        _state.update { if (it.key == key && it.message != null) it.copy(message = null) else it }
    }

    /** 말로 넣기를 못 할 때 그 입력 줄에 안내만 띄운다(권한 거부 등). 입력 줄은 그대로 쓸 수 있다 */
    fun fail(key: String, text: String) {
        show(State(key = key, message = Message(text, isError = true)))
    }

    /** 상태를 바꾸고, 안내가 있으면 잠시 뒤 지운다. 그사이 다른 안내로 바뀌었으면 그대로 둔다 */
    private fun show(next: State) {
        _state.value = next
        val shown = next.message ?: return
        main.postDelayed({ _state.update { if (it.message === shown) it.copy(message = null) else it } }, MESSAGE_MS)
    }

    private fun listen(app: Context, key: String, onDevice: Boolean) {
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } else {
            SpeechRecognizer.createSpeechRecognizer(app)
        }
        recognizer = r
        r.setRecognitionListener(Listener(app, key, onDevice))
        // 기본 인식은 짧은 말소리가 Google·삼성 서버로 갈 수 있어 처음 한 번 알린다
        val note = if (!onDevice && firstOnlineUse(app)) Message("기기 안 음성 인식이 없어 휴대폰 기본 인식(인터넷)을 쓰고 있어요.", isError = false) else null
        show(State(key = key, listening = true, message = note))
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // 기본 인식도 기기 안에서 할 수 있으면 그쪽을 쓰게 한다
                .apply { if (!onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) },
        )
        Log.i(TAG, "listen onDevice=$onDevice")
    }

    private fun firstOnlineUse(app: Context): Boolean {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONLINE_NOTED, false)) return false
        prefs.edit().putBoolean(KEY_ONLINE_NOTED, true).apply()
        return true
    }

    private fun release() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    /** 기기 안 인식이 한국어를 못 하거나 쓸 수 없을 때 나는 오류: 이때만 기본 인식으로 다시 듣는다 */
    private fun needsFallback(code: Int) = code in setOf(
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    )

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "잘 못 들었어요. 마이크를 다시 누르고 말해 주세요."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없어 말로 넣을 수 없어요. 설정 › 앱 › WorkBrain › 권한에서 마이크를 허용해 주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> "다른 앱이 마이크를 쓰고 있어요. 잠시 뒤 다시 눌러 주세요."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "인터넷이 불안정해 알아듣지 못했어요. 다시 누르거나 글로 적어 주세요."
        else -> "말로 넣지 못했어요. 다시 누르거나 글로 적어 주세요."
    }

    private class Listener(private val app: Context, private val key: String, private val onDevice: Boolean) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.update { if (it.key == key && it.listening) it.copy(partial = text) else it }
        }

        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            release()
            if (text.isBlank()) {
                show(State(key = key, message = Message(errorText(SpeechRecognizer.ERROR_NO_MATCH), isError = true)))
                return
            }
            // 처음 한 번 안내(기본 인식)는 결과가 나온 뒤에도 남겨 둔다
            _state.update { State(key = key, message = it.message?.takeIf { m -> it.key == key && !m.isError }) }
            _heard.value = Heard(key, text, ++seq)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "error code=$error onDevice=$onDevice")
            release()
            if (onDevice && needsFallback(error) && SpeechRecognizer.isRecognitionAvailable(app)) {
                listen(app, key, onDevice = false)
                return
            }
            show(State(key = key, message = Message(errorText(error), isError = true)))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
