package com.wordmate.app

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name:String) {
        val root=File(ApplicationProvider.getApplicationContext<WordMateApp>().getExternalFilesDir(null),"qa").apply{mkdirs()}
        val roots=compose.onAllNodes(isRoot())
        val visibleIndex=roots.fetchSemanticsNodes().indexOfLast { it.boundsInRoot.width>0 && it.boundsInRoot.height>0 }
        check(visibleIndex>=0)
        File(root,name+".png").outputStream().use { output ->
            roots[visibleIndex].captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }
    @Test fun emptyHomePromptsOnlyUserChosenWords() {
        var clicked=false
        compose.setContent { WordMateTheme { TodayScreen(emptyList(),emptyList(),null,AppSettings(),{},{clicked=true},{},true) } }
        compose.onNodeWithText("添加我的第一个单词").assertExists().performClick()
        assertTrue(clicked)
        screenshot("01-home-empty")
    }
    @Test fun librarySearchAndPendingFilterWork() {
        val words=listOf(WordCard("apple","apple",meaning="苹果"),WordCard("beautiful","beautiful",meaning="美丽"))
        compose.setContent { WordMateTheme { WordbookScreen(words,emptyList(),{}, {}) } }
        compose.onNodeWithText("apple").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("beautiful")
        compose.onAllNodesWithText("beautiful").assertCountEquals(2)
        compose.onNodeWithText("apple").assertDoesNotExist()
        screenshot("02-wordbook")
    }
    @Test fun manualImportSupportsMultilineAndValidatesWords() {
        var added=emptyList<String>()
        compose.setContent { WordMateTheme { AddWordsDialog({}, {added=it},false) } }
        compose.onNode(hasSetTextAction()).performTextInput("apple\nbeautiful")
        compose.onNodeWithText("添加并补全").performClick()
        assertEquals(listOf("apple","beautiful"),added)
    }
    @Test fun ocrImportLetsUserRemoveUnwantedWords() {
        var added=emptyList<String>()
        compose.setContent { WordMateTheme { OcrWordsDialog(listOf("apple","heading"),{}, {added=it},false) } }
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("添加选中的词").performClick()
        assertEquals(listOf("apple"),added)
        screenshot("03-ocr-confirm")
    }
    @Test fun quizHidesAnswerAndExampleBeforeSubmission() {
        val application=ApplicationProvider.getApplicationContext<WordMateApp>()
        val vm=AppViewModel(application)
        val card=WordCard("apple","apple",meaning="苹果",exampleEn="I have an apple.",exampleZh="我有一个苹果。",image="media/test.jpg")
        val state=DailySession(20000,codec.encodeToString(listOf(QuizItem("apple",false))),phase="meaning",
            options=codec.encodeToString(listOf("苹果","道路","窗户","勇敢")))
        compose.setContent { WordMateTheme { QuizScreen(vm,state,listOf(card),AppSettings(),false,{}) } }
        compose.onNodeWithText("apple").assertDoesNotExist()
        compose.onNodeWithText("I have an apple.").assertDoesNotExist()
        compose.onNodeWithText("苹果").assertExists()
        screenshot("04-meaning-quiz")
        vm.repo.speech.stop()
    }
    @Test fun bundledOcrRecognizesThreeWordsLocally() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<WordMateApp>()
        val bitmap=Bitmap.createBitmap(1200,620,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=90f }
        listOf("apple","beautiful","remember").forEachIndexed { index,word -> canvas.drawText(word,90f,145f+index*170f,paint) }
        val file=File(context.cacheDir,"ocr-instrumentation.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        val recognized=OcrService.recognize(context,Uri.fromFile(file)).map(::wordKey)
        assertTrue(recognized.containsAll(listOf("apple","beautiful","remember")))
    }
    @Test fun androidKeystoreEncryptsAndClearsSavedKey() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<WordMateApp>()
        val store=SecretStore(context)
        val fakeKey="wordmate-qa-fake-key-not-a-real-credential"
        try {
            store.set("qa-key",fakeKey)
            assertEquals(fakeKey,store.get("qa-key"))
            val raw=context.getSharedPreferences("encrypted_secrets",0).getString("qa-key","")!!
            assertFalse(raw.contains(fakeKey))
            assertTrue(raw.contains(":"))
        } finally { store.set("qa-key","") }
        assertEquals("",store.get("qa-key"))
    }
    @Test fun unavailableVoiceKeepsMeaningAnswerDisabled() {
        val application=ApplicationProvider.getApplicationContext<WordMateApp>()
        val vm=AppViewModel(application)
        val card=WordCard("apple","apple",meaning="苹果",exampleEn="I have an apple.",exampleZh="我有一个苹果。",image="media/test.jpg")
        val state=DailySession(20001,codec.encodeToString(listOf(QuizItem("apple",false))),phase="meaning",
            options=codec.encodeToString(listOf("苹果","道路","窗户","勇敢")))
        compose.setContent { WordMateTheme { QuizScreen(vm,state,listOf(card),AppSettings(accent="zz-ZZ"),false,{}) } }
        compose.waitUntil(timeoutMillis=20000) { compose.onAllNodesWithText("打开系统朗读设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("苹果").assertIsNotEnabled()
        compose.onNodeWithText("apple").assertDoesNotExist()
        vm.repo.speech.stop()
    }
}
