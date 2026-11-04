package ai.ontheway.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import java.util.Locale

/** Pleos TextToSpeech in ON_DEVICE mode: the car's own engine, no app registration or network. */
class Voice(context: Context, private val log: (String) -> Unit) {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    @Volatile private var ready = false

    init {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    runCatching { tts.setLocale(Locale.KOREA) }
                }

                override fun onFailure(error: ErrorType) = log("음성 엔진 초기화 실패: $error")
            })
        }.onFailure { log("음성 엔진 없음: ${it.message}") }
    }

    fun say(text: String) {
        if (ready) runCatching { tts.speak(text, true) }.onFailure { log("음성 출력 실패: ${it.message}") }
    }

    fun release() = runCatching { tts.release() }
}
