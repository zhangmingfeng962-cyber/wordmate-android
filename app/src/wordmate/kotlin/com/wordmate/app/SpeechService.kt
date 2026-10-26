package com.wordmate.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

class SpeechService(private val context: Context,private val recordings:PronunciationService?=null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialized = CompletableDeferred<Boolean>()
    private var engine: TextToSpeech? = null
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var player: MediaPlayer? = null
    private var playbackWaiter: CompletableDeferred<Boolean>? = null
    private var running: Job? = null
    private val letterState = MutableStateFlow(-1)
    val letter: StateFlow<Int> = letterState
    init {
        engine = TextToSpeech(context) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { waiters.remove(utteranceId)?.complete(true) }
            @Deprecated("Required legacy callback") override fun onError(utteranceId: String?) { waiters.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { waiters.remove(utteranceId)?.complete(false) }
        })
        engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    }
    private suspend fun ready(accent: String): TextToSpeech {
        check(withTimeoutOrNull(10000) { initialized.await() } == true) { "手机尚无可用的朗读引擎，请在系统文字转语音设置中安装或启用英文语音。" }
        val tts = engine ?: error("朗读服务未启动")
        check(tts.setLanguage(Locale.forLanguageTag(accent)) >= TextToSpeech.LANG_AVAILABLE) { "当前朗读引擎缺少英文语音，请在系统设置下载英语语音包。" }
        val offline = tts.voices?.filter { it.locale.toLanguageTag().equals(accent, true) && !it.isNetworkConnectionRequired }
        offline?.firstOrNull()?.let { tts.voice = it }
        return tts
    }
    fun audioFile(word: String, accent: String, slow: Boolean): File = File(context.filesDir, "audio/" +
        digest((word + "|" + accent + "|" + slow + "|v1").toByteArray()) + ".wav").apply { parentFile?.mkdirs() }

    private suspend fun synthesize(word: String, accent: String, slow: Boolean, destination: File): Boolean {
        val tts = ready(accent)
        tts.setSpeechRate(if (slow) .65f else .9f)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        waiters[id] = done
        val temporary = File(destination.parentFile, destination.name + ".part")
        if (tts.synthesizeToFile(word, Bundle(), temporary, id) == TextToSpeech.ERROR) {
            waiters.remove(id); return false
        }
        val success = withTimeoutOrNull(25000) { done.await() } == true
        waiters.remove(id)
        if (success && temporary.length() > 44) return temporary.renameTo(destination)
        temporary.delete()
        return false
    }
    private suspend fun playFile(file: File,slow:Boolean=false): Boolean {
        val done = CompletableDeferred<Boolean>()
        playbackWaiter = done
        val activePlayer=MediaPlayer()
        try {
            player = activePlayer.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.path)
                setOnCompletionListener { done.complete(true) }
                setOnErrorListener { _, _, _ -> done.complete(false); true }
                prepare()
                if(slow)playbackParams=android.media.PlaybackParams().setSpeed(.7f).setPitch(1f)
                start()
            }
            return withTimeoutOrNull(60000) { done.await() } == true
        } finally { activePlayer.release(); if(player===activePlayer){player = null; playbackWaiter = null} }
    }
    fun speakWord(word:String,accent:String,slow:Boolean=false,systemFallback:Boolean=false,retry:Boolean=false,
        completed:(Result<Pronunciation?>)->Unit={}){
        stop()
        running=scope.launch{
            try{
                val recording=if(systemFallback)null else (recordings?:error("真人录音服务未就绪，可使用系统语音继续。")).resolve(word,accent,retry)
                val file=if(recording!=null)File(context.filesDir,recording.file)else audioFile(word,accent,slow).also{
                    if(!it.exists()||it.length()<44)check(synthesize(word,accent,slow,it)){"系统英文语音生成失败，请检查语音包。"}
                }
                check(playFile(file,recording!=null&&slow)){"读音播放失败，请重试或使用系统语音继续。"}
                completed(Result.success(recording))
            }catch(e:CancellationException){}
            catch(e:Exception){completed(Result.failure(e))}
        }
    }
    fun speak(word: String, accent: String, slow: Boolean = false, completed: (Result<Unit>) -> Unit = {}) {
        stop()
        running = scope.launch {
            try {
                val file = audioFile(word, accent, slow)
                if (!file.exists() || file.length() < 44) check(synthesize(word, accent, slow, file)) { "英文语音生成失败，请检查语音包后重试。" }
                check(playFile(file)) { "读音播放失败，请重试。" }
                completed(Result.success(Unit))
            } catch (e: CancellationException) { /* screen changed; must not unlock quiz */ }
            catch (e: Exception) { completed(Result.failure(e)) }
        }
    }
    fun spell(word: String, accent: String, completed: (Result<Unit>) -> Unit = {}) {
        stop()
        running = scope.launch {
            try {
                val tts = ready(accent)
                tts.setSpeechRate(.75f)
                for ((index, ch) in word.withIndex()) {
                    letterState.value = index
                    val text = SpannableString(ch.toString().uppercase(Locale.ROOT))
                    text.setSpan(TtsSpan.VerbatimBuilder(text.toString()).build(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val id = UUID.randomUUID().toString()
                    val done = CompletableDeferred<Boolean>()
                    waiters[id] = done
                    check(tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.ERROR) { "拼读失败，请检查朗读引擎。" }
                    check(withTimeoutOrNull(10000) { done.await() } == true) { "字母拼读未完成，请重试。" }
                    delay(160)
                }
                completed(Result.success(Unit))
            } catch (e: CancellationException) { }
            catch (e: Exception) { completed(Result.failure(e)) }
            finally { letterState.value = -1 }
        }
    }
    fun stop() {
        running?.cancel(); running = null
        engine?.stop()
        waiters.values.forEach { it.complete(false) }; waiters.clear()
        playbackWaiter?.complete(false)
        player?.let { runCatching { it.stop() } }
        letterState.value = -1
    }
}
