package com.wordmate.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=WordMateApp::class)
class QueueAndMixedTest {
    private lateinit var app:WordMateApp
    private lateinit var repo:WordRepository
    private val day=22000L
    @Before fun setup()=runBlocking{
        app=ApplicationProvider.getApplicationContext()
        app.deleteDatabase("wordmate.db");repo=WordRepository(app)
        repo.settings.save(AppSettings())
    }
    @After fun close(){repo.database.close()}
    private fun image(letter:Char):String {
        val path="media/"+letter.toString().repeat(64)+".jpg"
        File(app.filesDir,path).apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(letter.code.toByte(),2,3))}
        return path
    }
    private suspend fun word(id:String,hasImage:Boolean=false):WordCard {
        repo.addWords(listOf(id))
        val card=repo.dao.word(id)!!.copy(meaning="含义"+id,image=if(hasImage)image('a')else "")
        repo.dao.putWord(card);return card
    }
    @Test fun duplicateEnqueueDoesNotDuplicateWorkAndSeedsMeaning()=runBlocking{
        repo.addWords(listOf("apple","beautiful"))
        val queue=EnrichmentQueue(repo,FakeEnrichmentProvider(),false)
        assertEquals(2,queue.enqueue())
        assertEquals(0,queue.enqueue())
        assertEquals(2,repo.dao.jobs().size)
        assertTrue(repo.dao.words().all{it.textReady()})
    }
    @Test fun slowWordDoesNotBlockAnotherWord()=runBlocking{
        repo.addWords(listOf("slow","quick"))
        val gate=CompletableDeferred<Unit>()
        val provider=object:FakeEnrichmentProvider(){
            override suspend fun sentence(card:WordCard):WordCard?{if(card.id=="slow")gate.await();return super.sentence(card)}
        }
        val queue=EnrichmentQueue(repo,provider,false);queue.enqueue()
        val runner=async(Dispatchers.Default){queue.runBatch()}
        withTimeout(4000){while(repo.dao.job("quick")?.state!="success")delay(10)}
        assertEquals("running",repo.dao.job("slow")?.state)
        assertTrue(repo.dao.word("quick")!!.complete())
        gate.complete(Unit);runner.await();Unit
    }
    @Test fun editWhileRequestRunsCannotBeOverwritten()=runBlocking{
        repo.addWords(listOf("apple"))
        val gate=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        val provider=object:FakeEnrichmentProvider(){
            override suspend fun sentence(card:WordCard):WordCard?{entered.complete(Unit);gate.await();return super.sentence(card)}
            override suspend fun picture(card:WordCard):WordCard?=null
        }
        val queue=EnrichmentQueue(repo,provider,false);queue.enqueue()
        val runner=async(Dispatchers.Default){queue.runBatch()};entered.await()
        repo.saveCard(repo.dao.word("apple")!!.copy(meaning="用户的新词义",exampleEn="I want an apple.",exampleZh="用户译文"))
        gate.complete(Unit);runner.await()
        assertEquals("用户的新词义",repo.dao.word("apple")!!.meaning)
        assertEquals("用户译文",repo.dao.word("apple")!!.exampleZh)
        assertEquals("failed",repo.dao.job("apple")!!.state)
    }
    @Test fun pauseAndResumeKeepStageResults()=runBlocking{
        repo.addWords(listOf("apple"))
        val gate=CompletableDeferred<Unit>()
        val provider=object:FakeEnrichmentProvider(){
            override suspend fun picture(card:WordCard):WordCard?{gate.await();return super.picture(card)}
        }
        val queue=EnrichmentQueue(repo,provider,false);queue.enqueue()
        val runner=async(Dispatchers.Default){queue.runBatch()}
        withTimeout(4000){while(repo.dao.word("apple")!!.exampleEn.isBlank())delay(10)}
        queue.pause()
        runCatching{runner.await()}
        assertTrue(repo.dao.word("apple")!!.exampleEn.isNotBlank())
        assertEquals("pending",repo.dao.job("apple")!!.state)
        gate.complete(Unit);queue.resume();queue.runBatch()
        assertEquals("success",repo.dao.job("apple")!!.state)
    }
    @Test fun orphanPaidRequestRequiresExplicitRetry()=runBlocking{
        word("apple")
        repo.dao.putJob(EnrichmentJob("apple",state="running",stage="AI 图片生成"))
        EnrichmentQueue(repo,FakeEnrichmentProvider(),false).runBatch()
        assertEquals("failed",repo.dao.job("apple")!!.state)
        assertTrue(repo.dao.job("apple")!!.error.contains("结果未知"))
    }
    @Test fun failedOnlyRetryDoesNotResetSuccessfulJobs()=runBlocking{
        word("apple");word("beautiful")
        repo.dao.putJob(EnrichmentJob("apple",state="success"))
        repo.dao.putJob(EnrichmentJob("beautiful",state="failed"))
        val queue=EnrichmentQueue(repo,FakeEnrichmentProvider(),false)
        assertEquals(1,queue.enqueue(failedOnly=true))
        assertEquals("success",repo.dao.job("apple")!!.state)
        assertEquals("pending",repo.dao.job("beautiful")!!.state)
    }
    @Test fun textOnlyWordsStartWithoutWaitingForPictures()=runBlocking{
        word("apple")
        val state=repo.startMixed(day)!!
        assertEquals(listOf("meaning","listening","spelling"),state.templates.map{it.kind})
        assertEquals(listOf("apple"),state.groups.flatten().map{it.wordId})
    }
    @Test fun groupingAndRoundsInterleaveFiveWords()=runBlocking{
        for(id in listOf("one","two","three","four","five","six"))word(id)
        val state=repo.startMixed(day)!!
        assertEquals(listOf(5,1),state.groups.map{it.size})
        assertEquals(List(5){"meaning"}+List(5){"listening"}+List(5){"spelling"},state.templates.map{it.kind})
        assertEquals(5,state.questions.take(5).map{it.wordId}.distinct().size)
    }
    @Test fun oneWordGetsThreeOfflineDistractorPictures()=runBlocking{
        val card=word("apple",true)
        val state=repo.startMixed(day)!!
        val question=state.questions.first()
        assertEquals("image",question.kind);assertEquals(4,question.pictures.size)
        assertEquals(3,question.pictures.count{it.id.startsWith("bank:")})
        assertEquals(1,repo.dao.words().size);assertEquals(24,repo.pictures.entries.size)
        assertTrue(question.pictures.any{it.id=="word:"+card.id})
        assertFalse(question.pictures.any{it.id=="bank:apple"})
    }
    @Test fun imageCompletionDoesNotInsertQuestionsIntoRunningGroup()=runBlocking{
        val card=word("apple")
        val before=repo.startMixed(day)!!
        repo.dao.putWord(card.copy(image=image('b')))
        assertEquals(before,repo.startMixed(day))
    }
    @Test fun duplicateAnswerAndRetryNeverUpgradeTwice()=runBlocking{
        word("apple")
        var state=repo.startMixed(day)!!
        val first=state.current()!!
        repo.answerMixed(day,first.id,"__forgot__",true)
        repo.answerMixed(day,first.id,"__forgot__",true)
        state=repo.dao.mixed(day)!!.decode()
        assertEquals(1,state.answers.size)
        repeat(24){
            state=repo.dao.mixed(day)!!.decode()
            if(state.finished)return@repeat
            val q=state.current()!!
            if(state.feedback)repo.nextMixed(day,q.id)
            else repo.answerMixed(day,q.id,if(q.kind in listOf("spelling","listening"))"apple" else state.cards[q.wordId]!!.meaning)
        }
        val review=repo.dao.review("apple")!!
        assertEquals(1,review.meaningMisses);assertEquals(0,review.spellingMisses)
        assertEquals(day+1,review.meaningDue);assertEquals(1,repo.dao.attempts().size)
        assertEquals(1,repo.dao.mixed(day)!!.decode().completed())
    }
    @Test fun partialLegacySpellingResumesDraftAndMeaningGrade()=runBlocking{
        word("apple")
        repo.dao.putSession(DailySession(day,codec.encodeToString(listOf(QuizItem("apple",true))),phase="spelling",
            chosen="含义apple",meaningCorrect=true,typed="app"))
        var state=repo.startMixed(day)!!
        assertEquals("listening",state.current()!!.kind)
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        assertEquals("spelling",state.current()!!.kind);assertEquals("app",state.draft)
        repo.answerMixed(day,state.current()!!.id,"apple")
        assertEquals(0,repo.dao.review("apple")!!.meaningMisses)
        assertEquals(1,repo.dao.attempts().size)
    }
    @Test fun completedLegacyDayKeepsItsCompletionAndDoesNotRegrade()=runBlocking{
        word("apple")
        repo.dao.putSession(DailySession(day,"[]",phase="done",completed=7,correct=4))
        val state=repo.startMixed(day)!!
        assertTrue(state.finished);assertEquals(7,state.completed());assertEquals(4,state.correct())
        assertTrue(repo.dao.attempts().isEmpty())
    }
    @Test fun mixedDraftAndHintSurviveDatabaseReopen()=runBlocking{
        word("apple")
        var state=repo.startMixed(day)!!
        repo.answerMixed(day,state.current()!!.id,"含义apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.draftMixed(day,state.current()!!.id,"ap");repo.hintMixed(day,state.current()!!.id)
        repo.database.close();repo=WordRepository(app)
        val resumed=repo.startMixed(day)!!
        assertEquals("ap",resumed.draft);assertTrue(resumed.assisted)
    }
    @Test fun backupVersionOneRemainsImportable()=runBlocking{
        word("apple")
        val file=File(app.cacheDir,"version-one.zip")
        repo.backup.export(Uri.fromFile(file))
        // A v1 file has no mixed-session section; defaults remain backward-compatible.
        val payload=BackupPayload(version=1,words=repo.dao.words(),reviews=repo.dao.reviews(),attempts=emptyList(),
            sessions=emptyList(),settings=AppSettings(),files=emptyMap())
        java.util.zip.ZipOutputStream(file.outputStream()).use{zip->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"));zip.write(codec.encodeToString(payload).toByteArray());zip.closeEntry()
        }
        assertEquals(1,repo.backup.restore(Uri.fromFile(file)))
        assertEquals("含义apple",repo.dao.word("apple")!!.meaning)
    }
    @Test fun brokenPictureCanBeSkippedWithoutAnIncorrectAnswer()=runBlocking{
        word("apple",true)
        val state=repo.startMixed(day)!!
        assertEquals("image",state.current()!!.kind)
        repo.skipBrokenPicture(day,state.current()!!.id)
        val next=repo.dao.mixed(day)!!.decode()
        assertEquals("meaning",next.current()!!.kind)
        assertTrue(next.answers.isEmpty())
        assertFalse(repo.dao.review("apple")!!.started)
    }
    @Test fun deletingAnUnfinishedWordDoesNotTrapSessionOnResume()=runBlocking{
        word("apple");word("book")
        val before=repo.startMixed(day)!!
        repo.delete(before.current()!!.wordId)
        val after=repo.startMixed(day)!!
        assertNotEquals(before.current()!!.wordId,after.current()!!.wordId)
        assertFalse(after.finished)
    }
    @Test fun wordsBecomingReadyLaterCanUseRemainingDailyQuota()=runBlocking{
        word("apple")
        repo.addWords(listOf("book"))
        var state=repo.startMixed(day)!!
        repo.answerMixed(day,state.current()!!.id,"含义apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        assertTrue(repo.dao.mixed(day)!!.decode().finished)
        repo.dao.putWord(repo.dao.word("book")!!.copy(meaning="书"))
        val resumed=repo.startMixed(day)!!
        assertFalse(resumed.finished);assertEquals("book",resumed.current()!!.wordId)
        assertEquals(1,resumed.completed())
    }
    @Test fun newlyReadyWordsCannotExceedCapturedDailyQuota()=runBlocking{
        repo.settings.save(AppSettings(newLimit=1))
        word("apple")
        var state=repo.startMixed(day)!!
        repo.answerMixed(day,state.current()!!.id,"含义apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        state=repo.dao.mixed(day)!!.decode()
        repo.answerMixed(day,state.current()!!.id,"apple");repo.nextMixed(day,state.current()!!.id)
        word("book")
        assertTrue(repo.startMixed(day)!!.finished)
    }
}
