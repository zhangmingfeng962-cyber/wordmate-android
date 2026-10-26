package com.wordmate.app

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EnrichmentQueue(
    private val repo: WordRepository,
    private val provider: EnrichmentProvider = DefaultEnrichmentProvider(repo),
    private val autoLaunch: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runnerLock = Mutex()
    private val enqueueLock = Mutex()
    @Volatile private var foreground: Job? = null
    @Volatile private var activeRun: Job? = null
    private val workName = "wordmate-enrichment-1.1"
    fun needs(card: WordCard) = !card.complete() || card.chunks().isEmpty() ||
        (card.image.isNotBlank() && !repo.pictures.exists(card.image))

    suspend fun enqueue(ids: List<String>? = null, failedOnly: Boolean = false): Int = enqueueLock.withLock {
        val jobs = repo.dao.jobs().associateBy { it.wordId }
        val targets = repo.dao.words().filter { card ->
            (ids == null || card.id in ids) && needs(card) &&
                (!failedOnly || jobs[card.id]?.state in setOf("failed", "partial"))
        }
        val queued = mutableListOf<String>()
        repo.database.withTransaction {
            for (original in targets) {
                val existing = repo.dao.job(original.id)
                if (existing?.state in setOf("pending", "running")) continue
                var card = repo.dao.word(original.id) ?: continue
                if (card.image.isNotBlank() && !repo.pictures.exists(card.image)) {
                    card = card.copy(image = "", imageCredit = "", revision = card.revision + 1)
                    repo.dao.putWord(card)
                }
                repo.dao.putJob(EnrichmentJob(card.id, contextRevision = card.contextRevision))
                queued += card.id
            }
        }
        if (targets.isNotEmpty()) repo.settings.update { it.copy(enrichmentPaused = it.aiAccountNotice.isNotBlank()) }
        if (autoLaunch) kick()
        // Local data is also seeded across the whole batch while two network jobs run.
        for (id in queued) {
            val card = repo.dao.word(id) ?: continue
            try {
                provider.local(card)?.let { patch ->
                    repo.database.withTransaction {
                        val latest = repo.dao.word(id) ?: return@withTransaction
                        if (latest.contextRevision == card.contextRevision) {
                            val merged = mergeMissingCard(latest, patch)
                            if (merged != latest) repo.dao.putWord(merged.copy(revision = latest.revision + 1))
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        queued.size
    }

    fun kick() {
        if (!autoLaunch) return
        scope.launch {
            if (repo.settings.get().enrichmentPaused || repo.dao.jobs().none { it.state in setOf("pending", "running") }) return@launch
            WorkManager.getInstance(repo.app).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EnrichmentWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS).build())
            if (foreground?.isActive != true) {
                foreground = scope.launch {
                    while (isActive && !repo.settings.get().enrichmentPaused) {
                        val batch = runBatch()
                        if (!batch || repo.dao.jobs().none { it.state == "pending" }) break
                        yield()
                    }
                }
            }
        }
    }
    suspend fun pause() {
        repo.settings.update { it.copy(enrichmentPaused = true) }
        activeRun?.cancel()
        if (autoLaunch) WorkManager.getInstance(repo.app).cancelUniqueWork(workName)
    }
    suspend fun resume() {
        repo.settings.update { it.copy(enrichmentPaused = false, aiAccountNotice = "") }
        requeueAccountJobs()
        kick()
    }
    suspend fun continueFree() {
        repo.settings.update { it.copy(aiEnabled = false, enrichmentPaused = false, aiAccountNotice = "") }
        requeueAccountJobs()
        kick()
    }
    private suspend fun requeueAccountJobs() {
        for (job in repo.dao.jobs().filter { it.state == "failed" && it.stage == "AI 账户不可用" })
            repo.dao.putJob(job.copy(state = "pending", stage = "等待补全", error = ""))
    }
    suspend fun accountFailed(error: ApiFailure) {
        if (error.accountBlocked()) repo.settings.update { it.copy(enrichmentPaused = true, aiAccountNotice = error.message.orEmpty()) }
    }
    suspend fun awaitStopped() { activeRun?.join() }
    suspend fun runBatch(): Boolean {
        if (!runnerLock.tryLock()) return false
        activeRun = currentCoroutineContext()[Job]
        try {
            if (repo.settings.get().enrichmentPaused) return false
            for (orphan in repo.dao.jobs().filter { it.state == "running" }) {
                repo.dao.putJob(if (orphan.stage.startsWith("AI"))
                    orphan.copy(state = "failed", error = "上次 AI 请求结果未知，请手动重试，避免重复调用", stage = "需要重试")
                else orphan.copy(state = "pending", stage = "恢复待处理任务"))
            }
            val deadline = SystemClock.elapsedRealtime() + 8 * 60 * 1000
            var processed = 0
            while (processed < 20 && SystemClock.elapsedRealtime() < deadline && !repo.settings.get().enrichmentPaused) {
                val pending = repo.dao.jobs().filter { it.state == "pending" }.take(2)
                if (pending.isEmpty()) break
                supervisorScope { pending.map { job -> launch { process(job) } }.joinAll() }
                processed += pending.size
            }
            return true
        } finally {
            withContext(NonCancellable) {
                for (job in repo.dao.jobs().filter { it.state == "running" }) {
                    repo.dao.putJob(if (repo.settings.get().aiAccountNotice.isNotBlank())
                        job.copy(state = "pending", stage = "等待处理 AI 账户", error = "")
                    else if (job.stage.startsWith("AI")) job.copy(state = "failed", stage = "需要重试",
                        error = "请求已中断，AI 结果未知，请手动重试")
                    else job.copy(state = "pending", stage = "已暂停，进度已保存"))
                }
            }
            activeRun = null
            runnerLock.unlock()
        }
    }
    private suspend fun process(job: EnrichmentJob) {
        val initial = repo.dao.word(job.wordId) ?: run { repo.dao.deleteJob(job.wordId); return }
        if (initial.contextRevision != job.contextRevision) {
            repo.dao.putJob(job.copy(state = "failed", stage = "词卡已编辑", error = "请重新补全当前资料"))
            return
        }
        repo.dao.putJob(job.copy(state = "running", stage = "本机词典"))
        try {
            val result = EnrichmentPipeline(provider).run(initial, repo.settings.get(), save = { patch ->
                repo.database.withTransaction {
                    val latest = repo.dao.word(job.wordId) ?: error("词条已删除")
                    check(latest.contextRevision == job.contextRevision) { "词卡已编辑，旧补全结果已丢弃，请重新补全" }
                    val merged = mergeMissingCard(latest, patch).copy(revision = latest.revision + 1)
                    repo.dao.putWord(merged)
                    merged
                }
            }, stage = { title ->
                if (repo.settings.get().enrichmentPaused) throw CancellationException("补全已暂停，阶段成果已保存")
                val current = repo.dao.job(job.wordId)
                check(current?.generation == job.generation) { "任务已更新" }
                repo.dao.putJob(current.copy(state = "running", stage = title, updatedAt = System.currentTimeMillis()))
            })
            val missing = result.missing()
            val error = if (missing.isBlank()) {
                if (result.card.chunks().isEmpty()) "尚无可靠分块；可正常学习，支持完整读音和逐字母拼读。" else ""
            } else "待补：" + missing + "。" +
                result.errors.joinToString("；").ifBlank { "可稍后重试、手动完善或配置 AI。" }
            val state = when { missing.isBlank() -> "success"; result.errors.isNotEmpty() -> "failed"; else -> "partial" }
            repo.database.withTransaction {
                val latest = repo.dao.word(job.wordId) ?: return@withTransaction
                if (latest.contextRevision != job.contextRevision) error("词卡已编辑，请重新补全")
                repo.dao.putWord(latest.copy(error = error))
                repo.dao.putJob(job.copy(state = state,
                    stage = if (missing.isBlank()) "资料已齐全" else "部分资料待补", error = error, updatedAt = System.currentTimeMillis()))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.accountBlocked()) accountFailed(e)
            repo.database.withTransaction {
                repo.dao.word(job.wordId)?.let { latest ->
                    val message = ApiErrors.redact(e.message.orEmpty())
                    if (latest.contextRevision == job.contextRevision) repo.dao.putWord(latest.copy(error = message))
                    repo.dao.putJob(job.copy(state = "failed", stage = if (e is ApiFailure && e.accountBlocked()) "AI 账户不可用" else "需要重试",
                        error = message, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
}

class EnrichmentWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as WordMateApp).repository
        if (repo.settings.get().enrichmentPaused) return Result.success()
        repo.queue.runBatch()
        if (repo.settings.get().enrichmentPaused) return Result.success()
        return if (repo.dao.jobs().any { it.state in setOf("pending", "running") }) Result.retry() else Result.success()
    }
}
