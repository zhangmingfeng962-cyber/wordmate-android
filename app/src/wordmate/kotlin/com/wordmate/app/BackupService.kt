package com.wordmate.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable data class BackupPayload(
    val version: Int = 3,
    val words: List<WordCard>,
    val reviews: List<ReviewState>,
    val attempts: List<ReviewAttempt>,
    val sessions: List<DailySession>,
    val settings: AppSettings,
    val files: Map<String, String>,
    val mixedSessions: List<MixedSessionRecord> = emptyList(),
    val pronunciations:List<Pronunciation> = emptyList()
)

object BackupSafety {
    fun allowedMedia(path: String) = Regex("(media/[a-f0-9]{64}\\.jpg|audio/[a-f0-9]{64}\\.(wav|mp3|ogg))").matches(path)
    fun validate(payload: BackupPayload) {
        require(payload.version in 1..3) { "暂不支持这个备份版本" }
        require(payload.words.size <= 50000 && payload.attempts.size <= 1000000) { "备份记录过多" }
        require(payload.words.map { it.id }.distinct().size == payload.words.size) { "备份有重复单词" }
        val ids = payload.words.map { it.id }.toSet()
        payload.words.forEach {
            require(validWord(it.spelling) && it.id == wordKey(it.spelling)) { "备份包含无效单词" }
            require(it.image.isEmpty() || allowedMedia(it.image) && payload.files.containsKey(it.image)) { "备份缺少单词图片" }
            require(it.chunks().isEmpty() || LearningRules.validSegments(it.spelling, it.chunks())) { "备份包含无效分块" }
        }
        require(payload.reviews.all { it.wordId in ids && it.meaningStep in -1..5 && it.spellingStep in -1..5 }) { "复习数据无效" }
        require(payload.attempts.all { it.wordId in ids }) { "答题记录无效" }
        require(payload.sessions.all { it.index in 0..it.queue().size && it.phase in setOf("study","meaning","spelling","reveal","done","unavailable") }) { "学习会话无效" }
        payload.mixedSessions.forEach { record ->
            val state = record.decode()
            require(state.day == record.day && state.version in 2..3 && state.questions.size <= 3000 && state.templates.size<=20 && state.stageIndex in 0..4 && state.round>=1 &&
                state.groupIndex in 0..state.groups.size && state.position in 0..state.questions.size) { "混合练习记录无效" }
            fun validImage(path: String) = path.isBlank() || allowedMedia(path) ||
                Regex("asset:distractors/[a-z]+\\.svg").matches(path)
            require(state.cards.values.all { validWord(it.spelling) && validImage(it.image) } &&
                (state.questions+state.templates).all { it.kind in setOf("image","meaning","listening","spelling") && it.pictures.all { p -> validImage(p.image) } }) { "练习图片路径无效" }
            if (!state.finished) require((state.cards.values.map { it.image } +
                (state.questions+state.templates).flatMap { it.pictures.map(ImageOption::image) }).filter { it.startsWith("media/") }
                .all { it in payload.files }) { "备份缺少练习图片" }
        }
        require(payload.pronunciations.all{it.wordId in ids&&it.accent in listOf("en-US","en-GB")&&
            allowedMedia(it.file)&&it.file.startsWith("audio/")&&it.file in payload.files&&
            it.sourceUrl.startsWith("https://commons.wikimedia.org/")&&RecordingRules.allowedLicense(it.licenseUrl)}){"录音来源或文件无效"}
        require(payload.files.keys.all(::allowedMedia)) { "备份包含不支持的文件路径" }
        require(payload.files.values.all { Regex("[a-f0-9]{64}").matches(it) }) { "备份校验值无效" }
        require(payload.settings.newLimit in 0..100 && payload.settings.reviewLimit in 0..200 &&
            payload.settings.reminderHour in 0..23 && payload.settings.reminderMinute in 0..59) { "备份设置无效" }
    }
}

class BackupService(private val context: Context, private val database: WordDatabase, private val settings: SettingsStore) {
    private val dao = database.dao()
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val snapshot = database.withTransaction {
            BackupPayload(words = dao.words(), reviews = dao.reviews(), attempts = dao.attempts(), sessions = dao.sessions(),
                settings = settings.get(), files = emptyMap(), mixedSessions = dao.mixedSessions(),
                pronunciations=dao.pronunciations().filter{File(context.filesDir,it.file).isFile})
        }
        val referenced = snapshot.words.map { it.image }.filter(String::isNotEmpty).toMutableSet()
        snapshot.mixedSessions.map { it.decode() }.filter { !it.finished }.forEach { state ->
            referenced += state.cards.values.map { it.image }.filter { it.startsWith("media/") }
            referenced += (state.questions+state.templates).flatMap { it.pictures.map(ImageOption::image) }.filter { it.startsWith("media/") }
        }
        referenced+=snapshot.pronunciations.map{it.file}
        for (word in snapshot.words) for (text in listOf(word.spelling, word.exampleEn).filter(String::isNotEmpty))
            for (accent in listOf("en-US", "en-GB")) for (slow in listOf(false, true))
                referenced += "audio/" + digest((text + "|" + accent + "|" + slow + "|v1").toByteArray()) + ".wav"
        val files = mutableMapOf<String, File>()
        for (directory in listOf("media", "audio")) {
            File(context.filesDir, directory).listFiles()?.filter { it.isFile }?.forEach { file ->
                val name = directory + "/" + file.name
                if (name in referenced && BackupSafety.allowedMedia(name)) files[name] = file
            }
        }
        val hashes = files.mapValues { (_, file) -> digest(file.readBytes()) }
        val payload = snapshot.copy(files = hashes)
        BackupSafety.validate(payload)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份文件")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(codec.encodeToString(payload).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        payload.words.size
    }
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && (entry.name == "manifest.json" || BackupSafety.allowedMedia(entry.name))) { "备份含有无效文件路径" }
                    require(names.add(entry.name) && names.size < 30000) { "备份包含重复或过多文件" }
                    val destination = File(staging, entry.name).canonicalFile
                    require(destination.path.startsWith(staging.canonicalPath + File.separator)) { "备份路径不安全" }
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var entrySize = 0L
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            total += count; entrySize += count
                            require(total <= 256L * 1024 * 1024 && entrySize <= 64L * 1024 * 1024) { "备份解压后过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = File(staging, "manifest.json")
            require(manifest.isFile && manifest.length() <= 64L * 1024 * 1024) { "没有找到有效备份清单" }
            val payload = codec.decodeFromString<BackupPayload>(manifest.readText())
            BackupSafety.validate(payload)
            require(names == payload.files.keys + "manifest.json") { "备份文件清单不一致" }
            payload.files.forEach { (name, hash) -> require(digest(File(staging, name).readBytes()) == hash) { "备份文件校验失败" } }
            // Validation is complete before any current wordbook data is replaced.
            payload.files.forEach { (name, _) ->
                val target = File(context.filesDir, name); target.parentFile?.mkdirs()
                File(staging, name).copyTo(target, overwrite = true)
            }
            database.withTransaction {
                dao.clearJobs(); dao.clearMixed(); dao.clearSessions(); dao.clearAttempts(); dao.clearReviews(); dao.clearPronunciations(); dao.clearWords()
                payload.words.forEach { dao.putWord(it) }; payload.reviews.forEach { dao.putReview(it) }
                payload.attempts.forEach { dao.putAttempt(it) }; payload.sessions.forEach { dao.putSession(it) }
                payload.mixedSessions.forEach { dao.putMixed(it) }
                payload.pronunciations.forEach{dao.putPronunciation(it)}
            }
            settings.save(payload.settings)
            payload.words.size
        } finally { staging.deleteRecursively() }
    }
}
