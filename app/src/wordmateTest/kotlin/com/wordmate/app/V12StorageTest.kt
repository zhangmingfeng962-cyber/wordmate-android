package com.wordmate.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=WordMateApp::class)
class V12StorageTest {
    private lateinit var repo:WordRepository
    private lateinit var app:WordMateApp
    @Before fun setup()=runBlocking{
        app=ApplicationProvider.getApplicationContext();app.deleteDatabase("wordmate.db");repo=WordRepository(app)
        repo.settings.save(AppSettings());repo.addWords(listOf("apple"));repo.dao.putWord(repo.dao.word("apple")!!.copy(meaning="苹果"))
    }
    @After fun close(){repo.database.close()}
    @Test fun v2HalfFinishedSpellingRetainsMeaningAndDraftWhileAddingListening()=runBlocking{
        val meaning=ExerciseQuestion(wordId="apple",kind="meaning")
        val spelling=ExerciseQuestion(wordId="apple",kind="spelling")
        val old=MixedSession(24000,listOf(listOf(QuizItem("apple",true))),questions=listOf(meaning,spelling),position=1,
            answers=listOf(ExerciseAnswer(meaning.id,"apple","meaning",true)),draft="app",cards=mapOf("apple" to repo.dao.word("apple")!!))
        repo.dao.putMixed(MixedSessionRecord(old.day,codec.encodeToString(old)))
        var state=repo.startMixed(old.day)!!
        assertEquals(3,state.version);assertEquals("listening",state.current()!!.kind)
        repo.answerMixed(old.day,state.current()!!.id,"apple");repo.nextMixed(old.day,state.current()!!.id)
        state=repo.dao.mixed(old.day)!!.decode();assertEquals("spelling",state.current()!!.kind);assertEquals("app",state.draft)
        repo.answerMixed(old.day,state.current()!!.id,"apple")
        assertEquals(1,repo.dao.attempts().size);assertEquals(0,repo.dao.review("apple")!!.meaningMisses)
        Unit
    }
    @Test fun v2AlreadyGradedWordsAreNeverRegraded()=runBlocking{
        val old=MixedSession(24000,listOf(listOf(QuizItem("apple",true))),gradedWords=listOf("apple"),completedWords=listOf("apple"))
        repo.dao.putMixed(MixedSessionRecord(old.day,codec.encodeToString(old)))
        val state=repo.startMixed(old.day)!!
        assertTrue(state.finished);assertEquals(1,state.completed());assertTrue(repo.dao.attempts().isEmpty());Unit
    }
    @Test fun v2HintedDraftRemainsAssistedAfterNewListeningStep()=runBlocking{
        val meaning=ExerciseQuestion(wordId="apple",kind="meaning")
        val spelling=ExerciseQuestion(wordId="apple",kind="spelling")
        val old=MixedSession(24000,listOf(listOf(QuizItem("apple",true))),questions=listOf(meaning,spelling),position=1,
            answers=listOf(ExerciseAnswer(meaning.id,"apple","meaning",true)),draft="app",assisted=true,
            cards=mapOf("apple" to repo.dao.word("apple")!!))
        repo.dao.putMixed(MixedSessionRecord(old.day,codec.encodeToString(old)))
        var s=repo.startMixed(old.day)!!
        repo.answerMixed(old.day,s.current()!!.id,"apple");repo.nextMixed(old.day,s.current()!!.id)
        s=repo.dao.mixed(old.day)!!.decode();assertTrue(s.assisted);assertEquals("app",s.draft)
        repo.answerMixed(old.day,s.current()!!.id,"apple");repo.nextMixed(old.day,s.current()!!.id)
        s=repo.dao.mixed(old.day)!!.decode();assertFalse(s.assisted);assertEquals("spelling",s.current()!!.kind)
        repo.answerMixed(old.day,s.current()!!.id,"apple")
        assertEquals(1,repo.dao.review("apple")!!.spellingMisses);assertEquals(1,repo.dao.attempts().size);Unit
    }
    @Test fun backupV3RestoresRecordingAndStageProgress()=runBlocking{
        val path="audio/"+"b".repeat(64)+".ogg"
        File(app.filesDir,path).apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(79,103,103,83,1,2,3))}
        val recording=Pronunciation("apple","en-US",path,"https://commons.wikimedia.org/wiki/File:En-us-apple.ogg","Dvortygirl",
            "CC BY-SA 3.0","https://creativecommons.org/licenses/by-sa/3.0/","US pronunciation of apple")
        repo.dao.putPronunciation(recording)
        val state=repo.startMixed(24000)!!
        repo.answerMixed(24000,state.current()!!.id,"__forgot__",true);repo.nextMixed(24000,state.current()!!.id)
        val saved=repo.dao.mixed(24000)
        val backup=File(app.cacheDir,"recording-v3.zip");repo.backup.export(Uri.fromFile(backup))
        ZipInputStream(backup.inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break
            if(entry.name=="manifest.json")assertEquals(3,org.json.JSONObject(String(zip.readBytes())).getInt("version"))
        }}
        repo.dao.clearPronunciations();repo.dao.clearMixed()
        repo.backup.restore(Uri.fromFile(backup))
        assertEquals(recording,repo.dao.pronunciation("apple","en-US"));assertEquals(saved,repo.dao.mixed(24000))
        assertArrayEquals(byteArrayOf(79,103,103,83,1,2,3),File(app.filesDir,path).readBytes());Unit
    }
    @Test fun v2BackupStillImportsWithoutRecordings()=runBlocking{
        val payload=BackupPayload(version=2,words=repo.dao.words(),reviews=repo.dao.reviews(),attempts=emptyList(),sessions=emptyList(),settings=AppSettings(),files=emptyMap())
        val file=File(app.cacheDir,"old-v2.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use{zip->zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"));zip.write(codec.encodeToString(payload).toByteArray());zip.closeEntry()}
        assertEquals(1,repo.backup.restore(Uri.fromFile(file)));assertTrue(repo.dao.pronunciations().isEmpty());Unit
    }
    @Test fun deletingEarlierAnsweredWordDoesNotSkipCurrentWord()=runBlocking{
        repo.addWords(listOf("book","car"))
        for(id in listOf("book","car"))repo.dao.putWord(repo.dao.word(id)!!.copy(meaning="含义"+id))
        var s=repo.startMixed(24000)!!
        val first=s.current()!!.wordId
        repo.answerMixed(24000,s.current()!!.id,"苹果");repo.nextMixed(24000,s.current()!!.id)
        s=repo.dao.mixed(24000)!!.decode();val current=s.current()!!.wordId
        repo.delete(first)
        assertEquals(current,repo.startMixed(24000)!!.current()!!.wordId);Unit
    }
}
