package com.gohiking.feature.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TTS 播报器（F-ALERT-40~44，DEV §4.9）：
 * - Android `TextToSpeech`，播报语言跟随 App 当前语言（F-ALERT-40，取 configuration 首个 locale，
 *   每次播报前重设——App 内语言切换后自然跟随）；
 * - 播报时对音乐「压声」：AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK（F-ALERT-42），播完立即放弃焦点；
 * - 引擎不可用 / 初始化失败 → 静默降级不播报不报错（F-ALERT-43）；
 * - 独立系统服务，不触碰 GPS 采集链路（F-ALERT-44）。
 * 非线程安全：仅主线程（记录页组合内）使用；[shutdown] 必须在离开组合时调用。
 */
class TtsSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this::onInit)
    private var ready = false
    private var languageSet = false

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* 播报短暂压声，无需处理变更 */ }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    /** 播报一条文案；QUEUE_FLUSH：连续触发时新提醒顶掉未播完的旧提醒 */
    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return // F-ALERT-43 静默降级
        audioManager?.requestAudioFocus(focusRequest)
        // F-ALERT-40：语言跟随 App 当前语言（configuration 首 locale，失败回退默认）
        val locale = appContext.resources.configuration.locales[0]
        val result = engine.setLanguage(locale)
        languageSet = result !in setOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
        // N-31：语言不可用时语音根本不会开始，UtteranceProgressListener 也就不会有任何回调
        // → 音频焦点永久被占用（用户的后台音乐/导航被持续压声）。这里直接归还焦点并返回。
        if (!languageSet) {
            audioManager?.abandonAudioFocusRequest(focusRequest)
            return
        }
        val id = "gohiking-alert-${System.currentTimeMillis()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                audioManager?.abandonAudioFocusRequest(focusRequest)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager?.abandonAudioFocusRequest(focusRequest)
            }

            // N-31：QUEUE_FLUSH 会顶掉上一条未播完的语音，那条语音的终态回调是 onStop。
            // 不覆写它 → 被顶掉的那次永远不归还焦点。
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                audioManager?.abandonAudioFocusRequest(focusRequest)
            }
        })
        // N-31：speak 返回 ERROR 时同样不会有终态回调，必须就地归还焦点
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        }
    }

    fun shutdown() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
