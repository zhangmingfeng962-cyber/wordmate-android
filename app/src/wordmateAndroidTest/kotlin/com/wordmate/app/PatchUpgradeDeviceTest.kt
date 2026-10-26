package com.wordmate.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run snapshot=before on 1.1.0, then snapshot=after on the same installed app upgraded to 1.1.1. */
@RunWith(AndroidJUnit4::class)
class PatchUpgradeDeviceTest {
    @Test fun preservesActualV11UserDataAndEncryptedKey() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WordMateApp>()
        val repo = app.repository
        val settings = repo.settings.get()
        val snapshot = JSONObject().put("words",codec.encodeToString(repo.dao.words()))
            .put("reviews",codec.encodeToString(repo.dao.reviews())).put("attempts",codec.encodeToString(repo.dao.attempts()))
            .put("sessions",codec.encodeToString(repo.dao.sessions())).put("mixed",codec.encodeToString(repo.dao.mixedSessions()))
            .put("newLimit",settings.newLimit).put("reviewLimit",settings.reviewLimit)
            .put("textBase",settings.textBase).put("textModel",settings.textModel)
            .put("imageBase",settings.imageBase).put("imageModel",settings.imageModel)
            .put("textKeyHash",digest(repo.secrets.get("text").toByteArray()))
            .put("imageKeyHash",digest(repo.secrets.get("image").toByteArray()))
        val root = File(app.getExternalFilesDir(null),"qa").apply { mkdirs() }
        val baseline = File(root,"v111-upgrade-baseline.json")
        if (InstrumentationRegistry.getArguments().getString("snapshot") == "before") {
            assertTrue(repo.dao.words().isNotEmpty())
            assertTrue(repo.secrets.has("text"))
            baseline.writeText(snapshot.toString(2))
        } else {
            assertTrue("Capture the baseline on the actual 1.1.0 APK first",baseline.exists())
            val old = JSONObject(baseline.readText())
            for (key in old.keys()) assertEquals("Upgrade changed " + key,old.get(key),snapshot.get(key))
            File(root,"v111-upgrade-evidence.json").writeText(JSONObject().put("all_snapshot_fields_preserved",true)
                .put("word_count",repo.dao.words().size).put("key_present_and_hash_unchanged",repo.secrets.has("text")).toString(2))
        }
        Unit
    }
}
