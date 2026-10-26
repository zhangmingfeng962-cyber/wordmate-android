package com.wordmate.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V111UiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        val app = ApplicationProvider.getApplicationContext<WordMateApp>()
        val nodes = compose.onAllNodes(isRoot())
        val index = nodes.fetchSemanticsNodes().indexOfLast { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        val target = if (name.endsWith("error-details")) compose.onNode(isDialog()) else nodes[index]
        File(app.getExternalFilesDir(null),"qa/" + name + ".png").apply { parentFile!!.mkdirs() }.outputStream().use {
            target.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun overdueNoticeExplainsCauseAndOffersFreeContinuation() {
        var free = false
        val notice = ApiErrors.service(403,"{\"error\":{\"code\":\"AccountOverdueError\"}}").message!!
        compose.setContent { WordMateTheme { EnrichmentPanel(listOf(EnrichmentJob("samples",state="failed")),true,{},{},{},{},notice,{free=true}) } }
        compose.onNodeWithText(notice).assertExists()
        compose.onNodeWithText("已处理账户，继续").assertIsEnabled()
        screenshot("v111-account-notice")
        compose.onNodeWithText("关闭 AI，先用免费资源").performClick()
        assertTrue(free)
    }
    @Test fun failureDetailsShowEachWordsSpecificCause() {
        val jobs = listOf(EnrichmentJob("samples",state="failed",stage="AI 账户不可用",error="HTTP 403，AccountOverdueError"),
            EnrichmentJob("complex",state="failed",stage="部分资料待补",error="图片请求等待超时"))
        compose.setContent { WordMateTheme { EnrichmentPanel(jobs,false,{},{},{},{}) } }
        compose.onNodeWithText("查看补全详情").performClick()
        compose.onNodeWithText("HTTP 403，AccountOverdueError").assertExists()
        compose.onNodeWithText("图片请求等待超时").assertExists()
        screenshot("v111-error-details")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("HTTP 403，AccountOverdueError").assertDoesNotExist()
    }
    @Test fun bundledFormLookupWorksOnAndroid() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WordMateApp>()
        val dictionary = app.repository.dictionary
        assertEquals("sample",dictionary.lookup("samples")!!.lemma)
        assertEquals("constitute",dictionary.lookup("constitutes")!!.lemma)
        assertTrue(dictionary.lookup("samples")!!.phonetic.isBlank())
        Unit
    }
}
