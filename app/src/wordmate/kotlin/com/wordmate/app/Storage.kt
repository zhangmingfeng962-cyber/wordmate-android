package com.wordmate.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Dao
interface WordDao {
    @Query("SELECT * FROM pronunciations WHERE wordId=:word AND accent=:accent") suspend fun pronunciation(word:String,accent:String): Pronunciation?
    @Query("SELECT * FROM pronunciations") suspend fun pronunciations(): List<Pronunciation>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPronunciation(value:Pronunciation)
    @Query("DELETE FROM pronunciations") suspend fun clearPronunciations()
    @Query("DELETE FROM pronunciations WHERE wordId=:word") suspend fun deletePronunciations(word:String)
    @Query("SELECT * FROM words ORDER BY createdAt DESC") fun observeWords(): Flow<List<WordCard>>
    @Query("SELECT * FROM reviews") fun observeReviews(): Flow<List<ReviewState>>
    @Query("SELECT * FROM enrichment_jobs ORDER BY updatedAt DESC") fun observeJobs(): Flow<List<EnrichmentJob>>
    @Query("SELECT * FROM enrichment_jobs") suspend fun jobs(): List<EnrichmentJob>
    @Query("SELECT * FROM enrichment_jobs WHERE wordId=:id") suspend fun job(id: String): EnrichmentJob?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putJob(job: EnrichmentJob)
    @Query("DELETE FROM enrichment_jobs WHERE wordId=:id") suspend fun deleteJob(id: String)
    @Query("DELETE FROM enrichment_jobs") suspend fun clearJobs()
    @Query("SELECT * FROM mixed_sessions WHERE day=:day") fun observeMixed(day: Long): Flow<MixedSessionRecord?>
    @Query("SELECT * FROM mixed_sessions WHERE day=:day") suspend fun mixed(day: Long): MixedSessionRecord?
    @Query("SELECT * FROM mixed_sessions") suspend fun mixedSessions(): List<MixedSessionRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMixed(value: MixedSessionRecord)
    @Query("DELETE FROM mixed_sessions") suspend fun clearMixed()
    @Query("SELECT * FROM words ORDER BY createdAt") suspend fun words(): List<WordCard>
    @Query("SELECT * FROM reviews") suspend fun reviews(): List<ReviewState>
    @Query("SELECT * FROM attempts ORDER BY timestamp") suspend fun attempts(): List<ReviewAttempt>
    @Query("SELECT * FROM sessions") suspend fun sessions(): List<DailySession>
    @Query("SELECT * FROM words WHERE id=:id") suspend fun word(id: String): WordCard?
    @Query("SELECT * FROM reviews WHERE wordId=:id") suspend fun review(id: String): ReviewState?
    @Query("SELECT * FROM sessions WHERE day=:day") suspend fun session(day: Long): DailySession?
    @Query("SELECT * FROM sessions WHERE day=:day") fun observeSession(day: Long): Flow<DailySession?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addWord(word: WordCard): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWord(word: WordCard)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReview(review: ReviewState)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putAttempt(attempt: ReviewAttempt)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(session: DailySession)
    @Query("DELETE FROM words WHERE id=:id") suspend fun deleteWord(id: String)
    @Query("DELETE FROM reviews WHERE wordId=:id") suspend fun deleteReview(id: String)
    @Query("DELETE FROM attempts WHERE wordId=:id") suspend fun deleteAttempts(id: String)
    @Query("DELETE FROM words") suspend fun clearWords()
    @Query("DELETE FROM reviews") suspend fun clearReviews()
    @Query("DELETE FROM attempts") suspend fun clearAttempts()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
}

@Database(entities = [WordCard::class, ReviewState::class, ReviewAttempt::class, DailySession::class, EnrichmentJob::class, MixedSessionRecord::class, Pronunciation::class], version = 3, exportSchema = false)
abstract class WordDatabase : RoomDatabase() {
    abstract fun dao(): WordDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN contextRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS enrichment_jobs (wordId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, stage TEXT NOT NULL, error TEXT NOT NULL, generation TEXT NOT NULL, contextRevision INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mixed_sessions (day INTEGER NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            }
        }
        val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS pronunciations (wordId TEXT NOT NULL, accent TEXT NOT NULL, file TEXT NOT NULL, sourceUrl TEXT NOT NULL, author TEXT NOT NULL, license TEXT NOT NULL, licenseUrl TEXT NOT NULL, description TEXT NOT NULL, PRIMARY KEY(wordId,accent))")
        }}
        fun open(context: Context) = Room.databaseBuilder(context, WordDatabase::class.java, "wordmate.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}

private val Context.preferences by preferencesDataStore("wordmate_settings")
class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings")
    val flow: Flow<AppSettings> = context.preferences.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { runCatching { codec.decodeFromString<AppSettings>(it[key] ?: "{}") }.getOrDefault(AppSettings()) }
    suspend fun save(settings: AppSettings) { context.preferences.edit { it[key] = codec.encodeToString(settings) } }
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.preferences.edit { prefs ->
            val current = runCatching { codec.decodeFromString<AppSettings>(prefs[key] ?: "{}") }.getOrDefault(AppSettings())
            prefs[key] = codec.encodeToString(transform(current))
        }
    }
    suspend fun get() = flow.first()
}

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
    private val alias = "wordmate_api_key_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Await durable key storage on IO before reporting success.
    suspend fun set(name: String, value: String) = withContext(Dispatchers.IO) {
        if (value.isBlank()) { check(prefs.edit().remove(name).commit()); return@withContext }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(name, data).commit())
    }
    fun get(name: String): String = runCatching {
        val raw = prefs.getString(name, null) ?: return ""
        val parts = raw.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    fun has(name: String) = get(name).isNotEmpty()
}

class LocalDictionary(private val context: Context) {
    @Volatile private var database: SQLiteDatabase? = null
    @Synchronized private fun db(): SQLiteDatabase {
        database?.let { return it }
        val path = File(context.noBackupFilesDir, "dictionary-v2.db")
        if (!path.exists()) {
            val temp = File(path.parentFile, "dictionary-v2.tmp")
            context.assets.open("dictionary.db").use { input -> temp.outputStream().use(input::copyTo) }
            check(temp.renameTo(path))
        }
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
    }
    data class Entry(val phonetic: String, val meanings: List<String>, val lemma: String = "")
    suspend fun lookup(word: String): Entry? = withContext(Dispatchers.IO) {
        db().rawQuery("SELECT phonetic,translation FROM dictionary WHERE word=? COLLATE NOCASE LIMIT 1", arrayOf(word)).use { c ->
            if (c.moveToFirst()) return@withContext Entry(c.getString(0), c.getString(1).split("\n").map { it.trim() }.filter { it.isNotBlank() })
        }
        db().rawQuery("SELECT d.word,d.translation FROM forms f JOIN dictionary d ON d.word=f.base WHERE f.form=? COLLATE NOCASE LIMIT 1", arrayOf(word)).use { c ->
            // Keep the learner's original spelling. A base word's IPA cannot pronounce its inflected form.
            if (!c.moveToFirst()) null else Entry("", c.getString(1).split("\n").map { it.trim() }.filter { it.isNotBlank() }, c.getString(0))
        }
    }
    suspend fun distractors(): List<String> = withContext(Dispatchers.IO) {
        db().rawQuery("SELECT translation FROM dictionary WHERE length(translation) BETWEEN 3 AND 45 ORDER BY RANDOM() LIMIT 80", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).substringBefore('\n')) }
        }
    }
    suspend fun wordOptions(card:WordCard,others:List<WordCard>):List<String> = withContext(Dispatchers.IO){
        val own=lookup(card.spelling)?.phonetic.orEmpty()
        fun ipa(s:String)=s.lowercase().replace(Regex("[\\s/ˈˌ.]"),"")
        val candidates=others.map{it.spelling to it.phonetic}.toMutableList()
        db().rawQuery("SELECT word,phonetic FROM dictionary WHERE length(word) BETWEEN 2 AND 18 ORDER BY RANDOM() LIMIT 150",null).use{c->
            while(c.moveToNext())candidates+=c.getString(0) to c.getString(1)
        }
        val distractors=candidates.filter{validWord(it.first)&&wordKey(it.first)!=card.id&&
            (own.isBlank()||it.second.isBlank()||ipa(own)!=ipa(it.second))}.distinctBy{wordKey(it.first)}.shuffled().take(3).map{it.first}
        if(distractors.size==3)(distractors+card.spelling).shuffled()else emptyList()
    }
}
