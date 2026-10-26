package com.wordmate.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=WordMateApp::class)
class StorageAndSessionTest {
    private lateinit var app: WordMateApp
    private lateinit var repo: WordRepository
    private val day=20000L
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        app.deleteDatabase("wordmate.db")
        repo=WordRepository(app)
    }
    @After fun teardown() { repo.database.close() }
    private suspend fun readyWord(): WordCard {
        repo.addWords(listOf("apple"))
        val relative="media/"+"a".repeat(64)+".jpg"
        val image=File(app.filesDir,relative);image.parentFile!!.mkdirs();image.writeBytes(byteArrayOf(1,2,3,4))
        val card=repo.dao.word("apple")!!.copy(meaning="苹果",exampleEn="I have an apple.",exampleZh="我有一个苹果。",image=relative)
        repo.dao.putWord(card)
        return card
    }
    @Test fun newBookIsEmptyAndDuplicateImportKeepsProgress()= runBlocking {
        assertTrue(repo.dao.words().isEmpty())
        val result=repo.addWords(listOf("apple","Apple"))
        assertEquals(listOf("apple"),result.first)
        val review=LearningRules.grade(ReviewState("apple"),day,true,false)
        repo.dao.putReview(review)
        val again=repo.addWords(listOf("APPLE"))
        assertTrue(again.first.isEmpty());assertEquals(1,again.second)
        assertEquals(review,repo.dao.review("apple"))
    }
    @Test fun firstRoundFailureIsPreservedAfterCorrectRetry()= runBlocking {
        readyWord()
        assertEquals("study",repo.startSession(day)!!.phase)
        repo.beginQuiz(day);repo.choose(day,"苹果");repo.submit(day,"aple")
        val failed=repo.dao.review("apple")!!
        val reveal=repo.dao.session(day)!!
        assertEquals("reveal",reveal.phase);assertEquals(2,reveal.queue().size)
        assertTrue(reveal.meaningCorrect);assertFalse(reveal.spellingCorrect)
        repo.next(day);assertEquals("meaning",repo.dao.session(day)!!.phase)
        repo.choose(day,"苹果");repo.submit(day,"apple");repo.next(day)
        assertEquals(failed,repo.dao.review("apple"))
        assertEquals(2,repo.dao.attempts().size)
        assertEquals(1,repo.dao.session(day)!!.completed)
        assertEquals(0,repo.dao.session(day)!!.correct)
        assertEquals("done",repo.dao.session(day)!!.phase)
    }
    @Test fun hintMarksOtherwiseCorrectSpellingAsWeak()= runBlocking {
        readyWord();repo.startSession(day);repo.beginQuiz(day);repo.choose(day,"苹果")
        repo.hint(day);repo.submit(day,"apple")
        assertFalse(repo.dao.session(day)!!.spellingCorrect)
        assertEquals(1,repo.dao.review("apple")!!.spellingMisses)
    }
    @Test fun doubleSubmissionDoesNotDuplicateAttempts()= runBlocking {
        readyWord();repo.startSession(day);repo.beginQuiz(day);repo.choose(day,"苹果")
        repo.submit(day,"apple");repo.submit(day,"apple")
        assertEquals(1,repo.dao.attempts().size);assertEquals(1,repo.dao.session(day)!!.completed)
    }
    @Test fun existingSessionIsNotRegeneratedWhenMoreWordsAreAdded()= runBlocking {
        readyWord();val initial=repo.startSession(day)!!
        repo.addWords(listOf("beautiful"))
        assertEquals(initial,repo.startSession(day))
    }
    @Test fun typedDraftAndMeaningSelectionSurviveReopen()= runBlocking {
        readyWord();repo.startSession(day);repo.beginQuiz(day);repo.choose(day,"苹果");repo.draft(day,"app")
        repo.database.close();repo=WordRepository(app)
        val resumed=repo.startSession(day)!!
        assertEquals("spelling",resumed.phase);assertEquals("app",resumed.typed);assertEquals("苹果",resumed.chosen)
    }
    @Test fun tomorrowPlansOnlyDueOwnWords()= runBlocking {
        readyWord();repo.startSession(day);repo.beginQuiz(day);repo.choose(day,"苹果");repo.submit(day,"apple");repo.next(day)
        val tomorrow=repo.startSession(day+1)!!
        assertEquals(listOf("apple"),tomorrow.queue().map{it.wordId})
        assertEquals("meaning",tomorrow.phase)
    }
    @Test fun backupRestoresMediaProgressAndDraftButNeverKeys()= runBlocking {
        val card=readyWord();repo.startSession(day);repo.beginQuiz(day);repo.choose(day,"苹果");repo.draft(day,"app")
        app.getSharedPreferences("encrypted_secrets",0).edit().putString("text","SENSITIVE-KEY-NOT-FOR-BACKUP").commit()
        val file=File(app.cacheDir,"roundtrip.zip")
        assertEquals(1,repo.backup.export(Uri.fromFile(file)))
        val zipContents=StringBuilder()
        ZipInputStream(file.inputStream()).use { zip ->
            while(true){val entry=zip.nextEntry?:break;zipContents.append(entry.name);zipContents.append(String(zip.readBytes()));zip.closeEntry()}
        }
        assertFalse(zipContents.contains("SENSITIVE-KEY"));assertFalse(zipContents.contains("encrypted_secrets"))
        repo.dao.clearSessions();repo.dao.clearReviews();repo.dao.clearWords()
        assertEquals(1,repo.backup.restore(Uri.fromFile(file)))
        assertEquals(card,repo.dao.word("apple"));assertEquals("app",repo.dao.session(day)!!.typed)
        assertArrayEquals(byteArrayOf(1,2,3,4),File(app.filesDir,card.image).readBytes())
    }
    @Test fun traversalBackupCannotReplaceCurrentBook()= runBlocking {
        readyWord()
        val file=File(app.cacheDir,"traversal.zip")
        ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../escape"));zip.write(byteArrayOf(1));zip.closeEntry() }
        assertTrue(runCatching{repo.backup.restore(Uri.fromFile(file))}.isFailure)
        assertNotNull(repo.dao.word("apple"))
    }
    @Test fun tamperedBackupCannotReplaceCurrentBook()= runBlocking {
        val card=readyWord()
        val relative=card.image
        val payload=BackupPayload(words=listOf(card),reviews=emptyList(),attempts=emptyList(),sessions=emptyList(),
            settings=AppSettings(),files=mapOf(relative to "0".repeat(64)))
        val file=File(app.cacheDir,"tampered.zip")
        ZipOutputStream(file.outputStream()).use{ zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(codec.encodeToString(payload).toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry(relative));zip.write(byteArrayOf(9));zip.closeEntry()
        }
        assertTrue(runCatching{repo.backup.restore(Uri.fromFile(file))}.isFailure)
        assertEquals(card,repo.dao.word("apple"))
    }
    @Test fun backupValidationRejectsMissingImagesAndInvalidSegments() {
        val bad=WordCard("apple","apple",segments=codec.encodeToString(listOf("wrong")),image="media/nope.jpg")
        val payload=BackupPayload(words=listOf(bad),reviews=emptyList(),attempts=emptyList(),sessions=emptyList(),settings=AppSettings(),files=emptyMap())
        assertTrue(runCatching{BackupSafety.validate(payload)}.isFailure)
        assertFalse(BackupSafety.allowedMedia("../secrets"));assertFalse(BackupSafety.allowedMedia("media/../../secret"))
    }
}
