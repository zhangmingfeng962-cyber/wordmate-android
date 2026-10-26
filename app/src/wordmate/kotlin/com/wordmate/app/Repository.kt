package com.wordmate.app

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class WordMateApp : Application(), ImageLoaderFactory {
    val repository by lazy { WordRepository(this) }
    override fun newImageLoader() = ImageLoader.Builder(this).components { add(SvgDecoder.Factory()) }.build()
}

class WordRepository(val app: Application) {
    val database = WordDatabase.open(app)
    val dao = database.dao()
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val dictionary = LocalDictionary(app)
    val content = ContentService(app, dictionary, secrets)
    val pictures = PictureBank(app)
    val queue by lazy { EnrichmentQueue(this) }
    val pronunciation by lazy { PronunciationService(this) }
    val speech by lazy { SpeechService(app,pronunciation) }
    val backup = BackupService(app, database, settings)
    val operationLock = Mutex()
    private val sessionLock = Mutex()
    val mixedLock = Mutex()
    val words = dao.observeWords()
    val reviews = dao.observeReviews()

    suspend fun addWords(values: List<String>): Pair<List<String>, Int> {
        val clean = values.map(String::trim).filter(String::isNotEmpty).distinctBy(::wordKey)
        require(clean.size <= 300) { "每次最多添加 300 个单词，请分批添加" }
        require(clean.all(::validWord)) { "请输入英文单词，允许单词内部的连字符或撇号；每行一个词。" }
        val added = mutableListOf<String>()
        var duplicates = 0
        database.withTransaction {
            clean.forEach { spelling ->
                val id = wordKey(spelling)
                if (dao.addWord(WordCard(id, spelling)) == -1L) duplicates++
                else { dao.putReview(ReviewState(id)); added += id }
            }
        }
        return added to duplicates
    }

    suspend fun enrich(id: String, progress: (String) -> Unit) {
        queue.enqueue(listOf(id))
        progress("已加入后台补全队列")
    }

    suspend fun saveCard(card: WordCard) {
        require(validWord(card.spelling))
        require(card.chunks().isEmpty() || LearningRules.validSegments(card.spelling, card.chunks())) { "分块拼接后必须与原单词完全相同" }
        require(card.exampleEn.isBlank() || ContentParsing.hasWord(card.exampleEn, card.spelling)) { "英文例句需要包含当前单词的完整拼写" }
        database.withTransaction {
            val current = dao.word(card.id) ?: return@withTransaction
            // Editing content invalidates today's cached question options.
            dao.putWord(card.copy(revision = current.revision + 1, contextRevision = current.contextRevision + 1, error = ""))
            val session = dao.session(today())
            if (session?.current()?.wordId == card.id && session.phase != "reveal") {
                dao.putSession(session.copy(phase = if (card.complete()) "study" else "unavailable",
                    options = "[]", chosen = "", typed = "", assisted = false))
            }
        }
    }
    suspend fun setImage(id: String, path: String, credit: String) {
        val card = dao.word(id) ?: return
        saveCard(card.copy(image = path, imageCredit = credit))
    }
    suspend fun delete(id: String) = operationLock.withLock {
        database.withTransaction { dao.deleteWord(id); dao.deleteReview(id); dao.deleteAttempts(id); dao.deleteJob(id); dao.deletePronunciations(id) }
    }
    private suspend fun options(card: WordCard): String {
        val meanings = dictionary.distractors()
        val choices = LearningRules.meaningOptions(card.meaning, meanings)
        return codec.encodeToString(choices)
    }
    suspend fun startSession(day: Long): DailySession? = sessionLock.withLock {
        dao.session(day)?.let { return@withLock it }
        val words = dao.words().filter { it.image.isNotBlank() && runCatching { content.mediaFile(it.image).isFile }.getOrDefault(false) }
        val session = LearningRules.plan(words, dao.reviews(), day, settings.get()) ?: return@withLock null
        val card = dao.word(session.current()!!.wordId)!!
        val saved = session.copy(options = options(card))
        dao.putSession(saved)
        saved
    }
    suspend fun beginQuiz(day: Long) = sessionLock.withLock {
        val session = dao.session(day) ?: return@withLock
        if (session.phase != "study") return@withLock
        val card = dao.word(session.current()?.wordId.orEmpty()) ?: return@withLock
        dao.putSession(session.copy(phase = "meaning", options = options(card), chosen = "", typed = "", assisted = false))
    }
    suspend fun choose(day: Long, choice: String) = sessionLock.withLock {
        val session = dao.session(day) ?: return@withLock
        if (session.phase != "meaning") return@withLock
        val card = dao.word(session.current()?.wordId.orEmpty()) ?: return@withLock
        require(choice in session.choices() || choice == "__forgot__")
        dao.putSession(session.copy(chosen = choice, meaningCorrect = choice == card.meaning, phase = "spelling"))
    }
    suspend fun draft(day: Long, typed: String) = sessionLock.withLock {
        dao.session(day)?.let { if (it.phase == "spelling") dao.putSession(it.copy(typed = typed.take(100))) }
    }
    suspend fun hint(day: Long) = sessionLock.withLock {
        dao.session(day)?.let { if (it.phase == "spelling") dao.putSession(it.copy(assisted = true)) }
    }
    suspend fun submit(day: Long, typed: String, forgot: Boolean = false) = sessionLock.withLock {
        database.withTransaction {
            val session = dao.session(day) ?: return@withTransaction
            if (session.phase != "spelling") return@withTransaction
            val item = session.current() ?: return@withTransaction
            val card = dao.word(item.wordId) ?: return@withTransaction
            val spelling = !forgot && !session.assisted && LearningRules.spellingCorrect(typed, card.spelling)
            val meaning = session.meaningCorrect
            val attempt = ReviewAttempt(day.toString() + ":" + session.index, card.id, day, meaning, spelling, item.retry,
                session.assisted, typed.take(100))
            dao.putAttempt(attempt)
            if (!item.retry) {
                val old = dao.review(card.id) ?: ReviewState(card.id)
                dao.putReview(LearningRules.grade(old, day, meaning, spelling))
            }
            var queue = session.queue()
            if ((!meaning || !spelling) && !item.retry && queue.none { it.wordId == item.wordId && it.retry })
                queue = queue + item.copy(newWord = false, retry = true)
            dao.putSession(session.copy(items = codec.encodeToString(queue), typed = typed.take(100),
                spellingCorrect = spelling, phase = "reveal",
                completed = session.completed + if (item.retry) 0 else 1,
                correct = session.correct + if (!item.retry && meaning && spelling) 1 else 0))
        }
    }
    suspend fun next(day: Long) = sessionLock.withLock {
        val session = dao.session(day) ?: return@withLock
        var index = session.index + 1
        var next: WordCard? = null
        val queue = session.queue()
        while (index < queue.size) {
            val card = dao.word(queue[index].wordId)
            if (card?.complete() == true && runCatching { content.mediaFile(card.image).isFile }.getOrDefault(false)) { next = card; break }
            index++
        }
        if (next == null) dao.putSession(session.copy(index = queue.size, phase = "done"))
        else dao.putSession(session.copy(index = index, phase = if (queue[index].newWord) "study" else "meaning",
            options = options(next), chosen = "", typed = "", assisted = false, meaningCorrect = false, spellingCorrect = false))
    }
}
