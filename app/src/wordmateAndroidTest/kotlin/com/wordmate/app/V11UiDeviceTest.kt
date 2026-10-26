package com.wordmate.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V11UiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bulkButtonsRemainAvailableWithRunningJobs() {
        var all=false;var retry=false;var paused=false
        val jobs=listOf(EnrichmentJob("apple",state="running",stage="免费例句 / 图片（并行）"),EnrichmentJob("book",state="failed"))
        compose.setContent { WordMateTheme { EnrichmentPanel(jobs,false,{all=true},{retry=true},{paused=true},{}) } }
        compose.onNodeWithText("一键补全全部").assertIsEnabled().performClick()
        compose.onNodeWithText("重试失败项").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("暂停补全").performClick()
        assertTrue(all && retry && paused)
    }
    @Test fun fourBundledSvgChoicesAreSelectable() {
        var selected=""
        val options=listOf("apple","dog","car","tree").map { ImageOption("bank:"+it,"asset:distractors/"+it+".svg") }
        compose.setContent { WordMateTheme { Box(Modifier.padding(20.dp)){PictureChoices(options,true){selected=it}} } }
        for(index in 1..4)compose.onNodeWithContentDescription("图片选项 "+index).assertExists()
        compose.waitUntil(timeoutMillis=10000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"图片已加载")).fetchSemanticsNodes().size==4 }
        compose.onNodeWithContentDescription("图片选项 2").performClick()
        assertEquals("bank:dog",selected)
    }
    @Test fun pausedQueueHasExplicitResumeAction() {
        var resumed=false
        compose.setContent { WordMateTheme { EnrichmentPanel(listOf(EnrichmentJob("apple")),true,{},{},{},{resumed=true}) } }
        compose.onNodeWithContentDescription("继续补全").assertIsEnabled().performClick()
        assertTrue(resumed)
    }
}
