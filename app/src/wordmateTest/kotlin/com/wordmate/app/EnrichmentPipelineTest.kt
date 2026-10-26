package com.wordmate.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

open class FakeEnrichmentProvider : EnrichmentProvider {
    var textCalls = 0
    var imageCalls = 0
    override suspend fun local(card: WordCard): WordCard? = WordCard(card.id, card.spelling, meaning = "测试词义",
        segments = codec.encodeToString(listOf(card.spelling)))
    override suspend fun sentence(card: WordCard): WordCard? = WordCard(card.id, card.spelling, exampleEn = "This is " + card.spelling + ".", exampleZh = "这是例句。")
    override suspend fun picture(card: WordCard): WordCard? = WordCard(card.id, card.spelling, image = "media/fake.jpg", imageCredit = "fixture")
    override suspend fun textAi(card: WordCard, settings: AppSettings): WordCard? { textCalls++; return null }
    override suspend fun imageAi(card: WordCard, settings: AppSettings): WordCard? { imageCalls++; return null }
    override fun canText(settings: AppSettings) = settings.aiEnabled
    override fun canImage(settings: AppSettings) = settings.aiEnabled
}
@OptIn(ExperimentalCoroutinesApi::class)
class EnrichmentPipelineTest {
    @Test fun textIsSavedBeforeSlowImageFinishes() = runTest {
        val provider = object : FakeEnrichmentProvider() {
            override suspend fun sentence(card: WordCard): WordCard? { delay(20); return super.sentence(card) }
            override suspend fun picture(card: WordCard): WordCard? { delay(1000); return super.picture(card) }
        }
        var saved = WordCard("apple","apple")
        val run = async {
            EnrichmentPipeline(provider, 100).run(saved, AppSettings(), { saved = mergeMissingCard(saved,it); saved }, {})
        }
        runCurrent(); assertEquals("测试词义",saved.meaning)
        advanceTimeBy(25); runCurrent()
        assertTrue(saved.exampleEn.isNotBlank()); assertTrue(saved.image.isEmpty()); assertFalse(run.isCompleted)
        advanceUntilIdle()
        val result = run.await()
        assertTrue(result.card.exampleEn.isNotBlank()); assertTrue(result.errors.any { it.contains("等待上限") })
    }
    @Test fun freeSentenceAndImageRunInParallel() = runTest {
        val provider = object : FakeEnrichmentProvider() {
            override suspend fun sentence(card: WordCard): WordCard? { delay(60); return super.sentence(card) }
            override suspend fun picture(card: WordCard): WordCard? { delay(60); return super.picture(card) }
        }
        var saved = WordCard("apple","apple")
        EnrichmentPipeline(provider).run(saved,AppSettings(),{saved=mergeMissingCard(saved,it);saved},{})
        assertEquals(60,testScheduler.currentTime); assertTrue(saved.complete())
    }
    @Test fun cancellationKeepsAlreadyCommittedFields() = runTest {
        val provider = object : FakeEnrichmentProvider() {
            override suspend fun picture(card: WordCard): WordCard? { awaitCancellation() }
        }
        var saved=WordCard("apple","apple")
        val run=launch { EnrichmentPipeline(provider).run(saved,AppSettings(),{saved=mergeMissingCard(saved,it);saved},{}) }
        runCurrent();run.cancelAndJoin()
        assertTrue(saved.meaning.isNotBlank());assertTrue(saved.exampleZh.isNotBlank());assertTrue(saved.image.isBlank())
    }
    @Test fun existingManualContentAndCachedImageArePreserved() = runTest {
        val card=WordCard("apple","apple",meaning="自定义意思",exampleEn="An apple falls.",exampleZh="自定义译文",
            image="media/keep.jpg",segments=codec.encodeToString(listOf("ap","ple")))
        var saved=card
        val result=EnrichmentPipeline(FakeEnrichmentProvider()).run(card,AppSettings(),{saved=mergeMissingCard(saved,it);saved},{})
        assertEquals(card.meaning,result.card.meaning);assertEquals(card.image,result.card.image)
        assertEquals(card.exampleEn,result.card.exampleEn);assertEquals(card.segments,result.card.segments)
    }
    @Test fun disabledAiDoesNotGetCalledOnFreeFailure() = runTest {
        val provider=object:FakeEnrichmentProvider(){
            override suspend fun sentence(card:WordCard):WordCard?=null
            override suspend fun picture(card:WordCard):WordCard?=null
        }
        var saved=WordCard("apple","apple")
        EnrichmentPipeline(provider).run(saved,AppSettings(),{saved=mergeMissingCard(saved,it);saved},{})
        assertEquals(0,provider.textCalls);assertEquals(0,provider.imageCalls)
    }
    @Test fun aiIsOnlyAFallbackAfterFreeProviders() = runTest {
        val events=mutableListOf<String>()
        val provider=object:FakeEnrichmentProvider(){
            override suspend fun sentence(card:WordCard):WordCard? {events+="free-text";return null}
            override suspend fun picture(card:WordCard):WordCard? {events+="free-image";return null}
            override suspend fun textAi(card:WordCard,settings:AppSettings):WordCard? {events+="ai-text";return super.sentence(card)}
            override suspend fun imageAi(card:WordCard,settings:AppSettings):WordCard? {events+="ai-image";return super.picture(card)}
        }
        var saved=WordCard("apple","apple")
        EnrichmentPipeline(provider).run(saved,AppSettings(aiEnabled=true),{saved=mergeMissingCard(saved,it);saved},{})
        assertTrue(events.indexOf("ai-text")>events.indexOf("free-text"))
        assertTrue(events.indexOf("ai-image")>events.indexOf("free-image"))
        assertTrue(saved.complete())
    }
}
