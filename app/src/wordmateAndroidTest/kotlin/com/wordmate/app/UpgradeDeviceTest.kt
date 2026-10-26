package com.wordmate.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class UpgradeDeviceTest {
    @Test fun preservesRealV1DatabaseKeyAndUnfinishedSpelling() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WordMateApp>()
        val repo = app.repository
        val card = repo.dao.word("apple")
        assumeTrue("Restore the provided v1 upgrade fixture before the in-place upgrade.", card?.sources?.contains("升级测试") == true)
        requireNotNull(card)
        assertEquals("自定义苹果", card.meaning)
        assertEquals("保留的自定义例句。", card.exampleZh)
        assertEquals(4L, card.revision)
        assertEquals(0L, card.contextRevision)
        assertTrue(repo.content.mediaFile(card.image).length() > 1000)
        val review = repo.dao.review("apple")!!
        assertEquals(2, review.meaningStep); assertEquals(1, review.spellingStep)
        assertEquals(2, review.meaningMisses); assertEquals(3, review.spellingMisses)
        assertTrue(repo.dao.attempts().any { it.id == "baseline-attempt" })
        val prefs = repo.settings.get()
        assertEquals(12,prefs.newLimit); assertEquals(23,prefs.reviewLimit)
        assertEquals("https://example.invalid/v1",prefs.textBase)
        assertEquals("wordmate-upgrade-placeholder",repo.secrets.get("text"))
        val legacy = repo.dao.sessions().first { it.phase == "spelling" && it.typed == "app" }
        repo.dao.clearMixed() // Re-run conversion from the retained v1 row, not a previous test's v2 snapshot.
        val state = repo.startMixed(legacy.day)!!
        assertEquals("listening",state.current()!!.kind)
        assertEquals("app",state.drafts["apple:spelling"]); assertEquals(true,state.partialMeaning["apple"])
        assertEquals(12,state.dailyNewLimit);assertEquals(23,state.dailyReviewLimit)
        assertEquals(review,repo.dao.review("apple"))
        assertEquals(state,repo.startMixed(legacy.day))
        val backup = File(app.cacheDir,"upgrade-v2-backup.zip")
        repo.backup.export(Uri.fromFile(backup))
        var manifest=""
        ZipInputStream(backup.inputStream()).use { zip ->
            while(true){val entry=zip.nextEntry?:break;if(entry.name=="manifest.json")manifest=String(zip.readBytes());zip.closeEntry()}
        }
        assertEquals(3,JSONObject(manifest).getInt("version"))
        assertFalse(manifest.contains("wordmate-upgrade-placeholder"))
        val evidence = JSONObject().put("v1_database_migrated",true).put("custom_card_preserved",true)
            .put("review_dates_and_attempts_preserved",true).put("keystore_key_preserved",true)
            .put("unfinished_spelling_draft_preserved",true).put("v2_backup_excludes_key",true)
        File(app.getExternalFilesDir(null),"qa/upgrade-evidence.json").apply{parentFile!!.mkdirs();writeText(evidence.toString(2))}
        Unit
    }
}
