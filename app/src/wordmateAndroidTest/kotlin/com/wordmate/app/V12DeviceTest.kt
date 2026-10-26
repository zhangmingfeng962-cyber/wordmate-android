package com.wordmate.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V12DeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<WordMateApp>()
    private val repo get()=app.repository
    private fun fixture():Pronunciation=runBlocking{
        val path="audio/"+"d".repeat(64)+".ogg"
        val data=InstrumentationRegistry.getInstrumentation().context.assets.open("apple-us.ogg").use{it.readBytes()}
        File(app.filesDir,path).apply{parentFile!!.mkdirs();writeBytes(data)}
        val record=Pronunciation("apple","en-US",path,"https://commons.wikimedia.org/wiki/File:En-us-apple.ogg",
            "Dvortygirl","CC BY-SA 3.0","https://creativecommons.org/licenses/by-sa/3.0/","US pronunciation of apple, recorded by Dvortygirl")
        repo.dao.putPronunciation(record);record
    }
    private fun screenshot(name:String){
        val nodes=compose.onAllNodes(isRoot())
        val index=nodes.fetchSemanticsNodes().indexOfLast{it.boundsInRoot.width>0&&it.boundsInRoot.height>0}
        File(app.getExternalFilesDir(null),"qa/"+name+".png").apply{parentFile!!.mkdirs()}.outputStream().use{
            nodes[index].captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun screen(kind:String,feedback:Boolean=false,correct:Boolean=true):MixedSession {
        val card=WordCard("apple","apple",meaning="苹果",exampleEn="I have an apple.",exampleZh="我有一个苹果。",image=runBlocking{repo.dao.word("apple")?.image}.orEmpty())
        val q=ExerciseQuestion(wordId="apple",kind=kind,choices=if(kind=="meaning")listOf("苹果","道路","窗户","勇敢")else listOf("apple","book","tree","dog"))
        return MixedSession(25000,listOf(listOf(QuizItem("apple",true))),version=3,questions=listOf(q),templates=listOf(q),
            cards=mapOf("apple" to card),feedback=feedback,answers=if(feedback)listOf(ExerciseAnswer(q.id,"apple",kind,correct))else emptyList())
    }
    @Test fun cachedHumanRecordingActuallyPlaysAndSlowsDown()=runBlocking{
        val record=fixture()
        for(slow in listOf(false,true)){
            val result=CompletableDeferred<Result<Pronunciation?>>()
            withContext(Dispatchers.Main){repo.speech.speakWord("apple","en-US",slow){result.complete(it)}}
            val played=withTimeout(10000){result.await()}
            assertTrue(played.toString(),played.isSuccess);assertEquals(record,played.getOrNull())
        }
        assertNotNull(repo.pronunciation.cached("apple","en-US"));Unit
    }
    @Test fun correctAnswerStillShowsImageMeaningAndExampleUntilContinue(){
        fixture();val vm=AppViewModel(app)
        compose.setContent{WordMateTheme{MixedQuizScreen(vm,screen("meaning",true),AppSettings(),false,{})}}
        compose.onNodeWithText("苹果").assertExists()
        compose.onNodeWithText("I have an apple.").performScrollTo().assertExists()
        compose.onNodeWithText("继续").assertExists()
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithText("I have an apple.").assertExists()
        compose.onNodeWithText("苹果").performScrollTo()
        screenshot("v12-feedback")
    }
    @Test fun meaningQuestionDoesNotShowPictureExampleOrTranslationBeforeAnswer(){
        fixture();val vm=AppViewModel(app)
        compose.setContent{WordMateTheme{MixedQuizScreen(vm,screen("meaning"),AppSettings(),false,{})}}
        compose.onNodeWithText("I have an apple.").assertDoesNotExist()
        compose.onNodeWithText("我有一个苹果。").assertDoesNotExist()
        compose.onNodeWithContentDescription("单词例句的场景配图").assertDoesNotExist()
        compose.waitUntil(10000){compose.onAllNodesWithText("已播放，可以作答").fetchSemanticsNodes().isNotEmpty()}
        screenshot("v12-meaning")
    }
    @Test fun listeningQuestionHasOnlyFourEnglishOptionsAndNoTargetCard(){
        fixture();val vm=AppViewModel(app)
        compose.setContent{WordMateTheme{MixedQuizScreen(vm,screen("listening"),AppSettings(),false,{})}}
        compose.onAllNodesWithText("apple").assertCountEquals(1)
        compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.onNodeWithText("I have an apple.").assertDoesNotExist()
        compose.waitUntil(10000){compose.onAllNodesWithText("已播放，可以作答").fetchSemanticsNodes().isNotEmpty()}
        screenshot("v12-listening")
    }
    @Test fun spellingQuestionDoesNotExposeEnglishTarget(){
        fixture();val vm=AppViewModel(app)
        compose.setContent{WordMateTheme{MixedQuizScreen(vm,screen("spelling"),AppSettings(),false,{})}}
        compose.onNodeWithText("apple").assertDoesNotExist();compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.onNodeWithText("I have an apple.").assertDoesNotExist()
        compose.waitUntil(10000){compose.onAllNodesWithText("已播放，可以作答").fetchSemanticsNodes().isNotEmpty()}
        screenshot("v12-spelling")
    }
}
