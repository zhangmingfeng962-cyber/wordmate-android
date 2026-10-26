package com.wordmate.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

interface EnrichmentProvider {
    suspend fun local(card: WordCard): WordCard?
    suspend fun sentence(card: WordCard): WordCard?
    suspend fun picture(card: WordCard): WordCard?
    suspend fun textAi(card: WordCard, settings: AppSettings): WordCard?
    suspend fun imageAi(card: WordCard, settings: AppSettings): WordCard?
    fun canText(settings: AppSettings): Boolean
    fun canImage(settings: AppSettings): Boolean
}

class DefaultEnrichmentProvider(private val repo: WordRepository) : EnrichmentProvider {
    override suspend fun local(card: WordCard): WordCard? = repo.dictionary.lookup(card.spelling)?.let {
        WordCard(card.id, card.spelling, meaning = it.meanings.firstOrNull().orEmpty(), meanings = codec.encodeToString(it.meanings),
            phonetic = it.phonetic, sources = "ECDICT · https://github.com/skywind3000/ECDICT" +
                if (it.lemma.isNotBlank()) "\n词形查询：" + card.spelling + " → " + it.lemma else "")
    }
    override suspend fun sentence(card: WordCard): WordCard? = repo.content.sentences(card.spelling)?.let {
        if (card.exampleEn.isNotBlank() && card.exampleEn != it.english) null
        else WordCard(card.id, card.spelling, exampleEn = it.english, exampleZh = it.chinese, sources = it.source)
    }
    override suspend fun picture(card: WordCard): WordCard? {
        for (candidate in repo.content.imageCandidates(card.imageQuery.ifBlank { card.spelling }).take(2)) {
            try {
                return WordCard(card.id, card.spelling, image = repo.content.downloadImage(candidate.url), imageCredit = candidate.credit)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return null
    }
    override fun canText(settings: AppSettings) = settings.aiEnabled && settings.textBase.isNotBlank() &&
        settings.textModel.isNotBlank() && repo.secrets.has("text")
    override fun canImage(settings: AppSettings) = settings.aiEnabled && settings.imageBase.isNotBlank() &&
        settings.imageModel.isNotBlank() && repo.secrets.has("image")
    override suspend fun textAi(card: WordCard, settings: AppSettings) =
        ContentParsing.mergeAi(WordCard(card.id, card.spelling, meaning = card.meaning, exampleEn = card.exampleEn, exampleZh = card.exampleZh),
            repo.content.ai(card, settings)).copy(sources = "AI 生成内容 · " + settings.textModel + "（可编辑校对）")
    override suspend fun imageAi(card: WordCard, settings: AppSettings) =
        WordCard(card.id, card.spelling, image = repo.content.generateImage(card, settings), imageCredit = "AI 场景插画 · " + settings.imageModel)
}

fun mergeMissingCard(current: WordCard, patch: WordCard): WordCard = current.copy(
    meaning = current.meaning.ifBlank { patch.meaning },
    meanings = if (current.meaningList().isEmpty()) patch.meanings else current.meanings,
    phonetic = current.phonetic.ifBlank { patch.phonetic },
    exampleEn = current.exampleEn.ifBlank { patch.exampleEn },
    exampleZh = current.exampleZh.ifBlank {
        if (current.exampleEn.isBlank() || current.exampleEn == patch.exampleEn) patch.exampleZh else ""
    },
    image = current.image.ifBlank { patch.image },
    imageCredit = if (current.image.isBlank() && patch.image.isNotBlank()) patch.imageCredit else current.imageCredit,
    segments = if (current.chunks().isEmpty() && LearningRules.validSegments(current.spelling, patch.chunks())) patch.segments else current.segments,
    imageQuery = current.imageQuery.ifBlank { patch.imageQuery },
    imagePrompt = current.imagePrompt.ifBlank { patch.imagePrompt },
    sources = if (patch.sources.isBlank() || current.sources.contains(patch.sources)) current.sources else appendSource(current.sources, patch.sources),
    error = ""
)

data class PipelineResult(val card: WordCard, val errors: List<String>) {
    fun missing(): String = card.missing()
}
class EnrichmentPipeline(private val provider: EnrichmentProvider, private val freeBudgetMs: Long = 20000) {
    suspend fun run(
        initial: WordCard, settings: AppSettings,
        save: suspend (WordCard) -> WordCard,
        stage: suspend (String) -> Unit
    ): PipelineResult = coroutineScope {
        var current = initial
        val lock = Mutex()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        suspend fun snapshot() = lock.withLock { current }
        suspend fun apply(patch: WordCard?) {
            if (patch != null) lock.withLock { current = save(patch) }
        }
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiFailure && e.accountBlocked()) throw e
                errors += (e.message?.take(200) ?: "资料获取失败")
            }
        }
        stage("本机词典")
        attempt { apply(provider.local(snapshot())) }
        stage("免费例句 / 图片（并行）")
        val freeComplete = withTimeoutOrNull(freeBudgetMs) {
            supervisorScope {
                val sentence = launch {
                    val card = snapshot()
                    if (card.exampleEn.isBlank() || card.exampleZh.isBlank()) attempt { apply(provider.sentence(card)) }
                }
                val image = launch {
                    val card = snapshot()
                    if (card.image.isBlank()) attempt { apply(provider.picture(card)) }
                }
                joinAll(sentence, image)
            }
            true
        } == true
        if (!freeComplete) errors += "免费查询已到等待上限，已保存已有资料"
        val card = snapshot()
        val needsText = card.meaning.isBlank() || card.exampleEn.isBlank() || card.exampleZh.isBlank() || card.chunks().isEmpty()
        if (needsText && provider.canText(settings)) {
            stage("AI 文字补缺")
            attempt { apply(provider.textAi(snapshot(), settings)) }
        }
        val updated = snapshot()
        if (updated.image.isBlank() && updated.exampleEn.isNotBlank() && provider.canImage(settings)) {
            stage("AI 图片生成")
            attempt { apply(provider.imageAi(snapshot(), settings)) }
        }
        if (needsText && !provider.canText(settings) && settings.aiEnabled) errors += "文字 AI 未配置完整"
        if (snapshot().image.isBlank() && settings.aiEnabled && !provider.canImage(settings)) errors += "图片 AI 未配置完整"
        PipelineResult(snapshot(), errors.distinct())
    }
}
