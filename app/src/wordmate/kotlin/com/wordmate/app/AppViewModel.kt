package com.wordmate.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as WordMateApp).repository
    val words = repo.words.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reviews = repo.reviews.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = repo.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val day = MutableStateFlow(today())
    val session = day.flatMapLatest { repo.dao.observeSession(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val mixed = day.flatMapLatest { repo.dao.observeMixed(it) }.map { it?.decode() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val enrichmentJobs = repo.dao.observeJobs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow("")
    val message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val ocrWords = MutableStateFlow<List<String>?>(null)
    val secretVersion = MutableStateFlow(0)
    private var job: Job? = null
    private var draftJob: Job? = null
    init { repo.queue.kick() }
    private fun run(label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            busy.value = label
            try { block() } catch (e: CancellationException) { message.tryEmit("操作已停止，已经保存的词条和进度会保留。") }
            catch (e: Exception) { message.tryEmit(e.message?.take(200) ?: "操作失败，请重试") }
            finally { busy.value = "" }
        }
    }
    fun cancel() { job?.cancel() }
    fun refreshDay() { day.value = today() }
    fun notify(text: String) { message.tryEmit(text) }
    fun add(values: List<String>, done: () -> Unit) = run("保存单词") {
        val (ids, duplicates) = repo.addWords(values)
        done()
        message.emit("已添加 " + ids.size + " 个词" + if (duplicates > 0) "，" + duplicates + " 个重复词已保留原进度" else "")
        enqueueInBackground(ids)
    }
    fun scan(uri: Uri) = run("正在本地识别图片") {
        val found = OcrService.recognize(getApplication(), uri)
        if (found.isEmpty()) message.emit("没有识别出英文单词，请换一张清晰图片或手动输入。")
        else ocrWords.value = found
    }
    private fun enqueueInBackground(ids: List<String>? = null, failedOnly: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = repo.queue.enqueue(ids, failedOnly)
                message.emit(if (count > 0) "已安排 " + count + " 个词；有释义的词可先练，句图后台补齐。" else "没有新增任务；已有任务不会重复创建。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.emit(e.message?.take(160) ?: "任务准备失败，请重试") }
        }
    }
    fun enrich(id: String) = enqueueInBackground(listOf(id))
    fun enrichAll(failedOnly: Boolean = false) = enqueueInBackground(failedOnly = failedOnly)
    fun pauseEnrichment() { viewModelScope.launch { repo.queue.pause() } }
    fun resumeEnrichment() { viewModelScope.launch { repo.queue.resume() } }
    fun continueFreeEnrichment() { viewModelScope.launch { repo.queue.continueFree() } }
    fun save(card: WordCard, done: () -> Unit) = run("保存词卡") { repo.saveCard(card); done() }
    fun delete(id: String, done: () -> Unit) = run("删除词条") { repo.delete(id); done() }
    fun image(id: String, uri: Uri) = run("保存图片") {
        repo.setImage(id, repo.content.importImage(uri), "自己上传的图片")
    }
    fun selectImage(id: String, candidate: ImageCandidate, done: () -> Unit) {
        viewModelScope.launch {
            try { repo.setImage(id, repo.content.downloadImage(candidate.url), candidate.credit); done() }
            catch (e: Exception) { message.emit(e.message?.take(160) ?: "图片保存失败") }
        }
    }
    fun generateImage(id: String) {
        viewModelScope.launch {
            try {
                val card = repo.dao.word(id) ?: return@launch
                val prefs = repo.settings.get()
                require(prefs.aiEnabled) { "请先在设置中开启 AI 补全并配置图片接口" }
                val expectedRevision = card.contextRevision
                val path = repo.content.generateImage(card, prefs)
                if (repo.dao.word(id)?.contextRevision == expectedRevision)
                    repo.setImage(id, path, "AI 场景插画 · " + prefs.imageModel)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiFailure) repo.queue.accountFailed(e)
                message.emit(e.message?.take(200) ?: "图片生成失败")
            }
        }
    }
    fun start(done: () -> Unit) = run("准备今日学习") {
        refreshDay()
        val session = repo.startMixed(day.value)
        if (session == null) message.emit("暂时没有可学习的词。请添加单词并补齐中文意思，图片和例句可后台准备。")
        else done()
    }
    fun beginQuiz() = run("准备抽查") { repo.beginQuiz(day.value) }
    fun choose(value: String) = run("保存答案") { repo.choose(day.value, value) }
    fun draft(value: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(150); repo.draft(day.value, value) }
    }
    fun hint() = run("记录提示") { repo.hint(day.value) }
    fun submit(value: String, forgot: Boolean = false) = run("记录学习进度") {
        draftJob?.cancel(); repo.submit(day.value, value, forgot)
    }
    fun next() = run("准备下一词") { repo.next(day.value) }
    fun answerMixed(questionId: String, value: String, forgot: Boolean = false) = run("记录答案") {
        draftJob?.cancel(); repo.answerMixed(day.value, questionId, value, forgot)
    }
    fun nextMixed(questionId: String) = run("准备下一题") { repo.nextMixed(day.value, questionId) }
    fun hintMixed(questionId: String) = run("记录提示") { repo.hintMixed(day.value, questionId) }
    fun skipBrokenPicture(questionId: String) = run("继续文字练习") { repo.skipBrokenPicture(day.value, questionId) }
    fun draftMixed(questionId: String, value: String) {
        draftJob?.cancel()
        val capturedDay = day.value
        draftJob = viewModelScope.launch { repo.draftMixed(capturedDay, questionId, value) }
    }
    fun saveSettings(value: AppSettings, textKey: String, imageKey: String) = run("保存设置") {
        require(value.newLimit in 0..100 && value.reviewLimit in 0..200 && value.newLimit + value.reviewLimit > 0) { "新词 0–100，复习词 0–200，不能同时为 0" }
        require(value.reminderHour in 0..23 && value.reminderMinute in 0..59) { "提醒时间无效" }
        if (value.textBase.isNotBlank()) ContentService.endpoint(value.textBase, "chat/completions")
        if (value.imageBase.isNotBlank()) ContentService.endpoint(value.imageBase, "images/generations")
        if (textKey.isNotBlank()) repo.secrets.set("text", textKey)
        if (imageKey.isNotBlank()) repo.secrets.set("image", imageKey)
        repo.settings.update { current -> value.copy(enrichmentPaused = current.enrichmentPaused, aiAccountNotice = current.aiAccountNotice) }
        if (!value.aiEnabled && repo.settings.get().aiAccountNotice.isNotBlank()) repo.queue.continueFree()
        Reminders.schedule(getApplication(), value)
        secretVersion.value++
        message.emit("设置已保存")
    }
    fun clearKey(image: Boolean) = run("清除密钥") {
        repo.secrets.set(if (image) "image" else "text", ""); secretVersion.value++; message.emit("密钥已清除")
    }
    fun test(image: Boolean) {
        viewModelScope.launch {
            try { message.emit(repo.content.testConnection(repo.settings.get(), image)) }
            catch (e: Exception) {
                if (e is ApiFailure) repo.queue.accountFailed(e)
                message.emit(e.message?.take(200) ?: "连接测试失败")
            }
        }
    }
    fun export(uri: Uri) = run("正在导出备份") {
        repo.operationLock.withLock { val count = repo.backup.export(uri); message.emit("备份完成，包含 " + count + " 个词；密钥未导出。") }
    }
    fun restore(uri: Uri) = run("正在校验并恢复备份") {
        repo.speech.stop()
        repo.queue.pause()
        repo.queue.awaitStopped()
        repo.operationLock.withLock {
            val count = repo.backup.restore(uri)
            Reminders.schedule(getApplication(), repo.settings.get())
            refreshDay(); message.emit("已恢复 " + count + " 个词及学习进度")
        }
    }
}
