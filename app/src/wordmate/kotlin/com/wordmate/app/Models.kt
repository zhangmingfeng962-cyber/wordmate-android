package com.wordmate.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.Locale

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun today(): Long = LocalDate.now().toEpochDay()
fun wordKey(value: String) = value.trim().lowercase(Locale.ROOT)
fun validWord(value: String) = value.length in 1..80 && Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").matches(value)

@Serializable
@Entity(tableName = "words")
data class WordCard(
    @PrimaryKey val id: String,
    val spelling: String,
    val meaning: String = "",
    val meanings: String = "[]",
    val phonetic: String = "",
    val exampleEn: String = "",
    val exampleZh: String = "",
    val image: String = "",
    val imageCredit: String = "",
    val segments: String = "[]",
    val imageQuery: String = "",
    val imagePrompt: String = "",
    val sources: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val contextRevision: Long = 0
) {
    fun meaningList(): List<String> = runCatching { codec.decodeFromString<List<String>>(meanings) }.getOrDefault(emptyList())
    fun chunks(): List<String> = runCatching { codec.decodeFromString<List<String>>(segments) }.getOrDefault(emptyList())
    fun complete() = meaning.isNotBlank() && exampleEn.isNotBlank() && exampleZh.isNotBlank() && image.isNotBlank()
    fun textReady() = meaning.isNotBlank()
    fun missing(): String = listOfNotNull(
        "中文意思".takeIf { meaning.isBlank() }, "英文例句".takeIf { exampleEn.isBlank() },
        "例句译文".takeIf { exampleZh.isBlank() }, "图片".takeIf { image.isBlank() }
    ).joinToString("、")
}

@Serializable
@Entity(tableName = "reviews")
data class ReviewState(
    @PrimaryKey val wordId: String,
    val started: Boolean = false,
    val meaningStep: Int = -1,
    val spellingStep: Int = -1,
    val meaningDue: Long = 0,
    val spellingDue: Long = 0,
    val lastDay: Long = -1,
    val meaningMisses: Int = 0,
    val spellingMisses: Int = 0
) { fun due() = minOf(meaningDue, spellingDue) }

@Serializable
@Entity(tableName = "attempts")
data class ReviewAttempt(
    @PrimaryKey val id: String,
    val wordId: String,
    val day: Long,
    val meaningCorrect: Boolean,
    val spellingCorrect: Boolean,
    val retry: Boolean,
    val assisted: Boolean,
    val submitted: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable data class QuizItem(val wordId: String, val newWord: Boolean, val retry: Boolean = false)
@Serializable
@Entity(tableName = "sessions")
data class DailySession(
    @PrimaryKey val day: Long,
    val items: String,
    val index: Int = 0,
    val phase: String = "study",
    val options: String = "[]",
    val chosen: String = "",
    val typed: String = "",
    val assisted: Boolean = false,
    val meaningCorrect: Boolean = false,
    val spellingCorrect: Boolean = false,
    val completed: Int = 0,
    val correct: Int = 0
) {
    fun queue(): List<QuizItem> = codec.decodeFromString(items)
    fun current() = queue().getOrNull(index)
    fun choices(): List<String> = codec.decodeFromString(options)
}

@Serializable data class AppSettings(
    val newLimit: Int = 10,
    val reviewLimit: Int = 20,
    val accent: String = "en-US",
    val reminder: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val aiEnabled: Boolean = false,
    val textBase: String = "",
    val textModel: String = "",
    val imageBase: String = "",
    val imageModel: String = "",
    val enrichmentPaused: Boolean = false,
    val aiAccountNotice: String = ""
)

object LearningRules {
    val intervals = listOf(1L, 3L, 7L, 14L, 30L, 60L)
    fun spellingCorrect(answer: String, expected: String) = wordKey(answer) == wordKey(expected)
    fun validSegments(word: String, segments: List<String>) =
        segments.isNotEmpty() && segments.all { it.isNotBlank() } && segments.joinToString("") == word

    fun grade(old: ReviewState, day: Long, meaning: Boolean, spelling: Boolean): ReviewState {
        if (old.lastDay == day) return old
        fun step(previous: Int, correct: Boolean) = if (correct) (previous + 1).coerceIn(0, intervals.lastIndex) else 0
        val ms = step(old.meaningStep, meaning)
        val ss = step(old.spellingStep, spelling)
        return old.copy(started = true, meaningStep = ms, spellingStep = ss,
            meaningDue = day + if (meaning) intervals[ms] else 1,
            spellingDue = day + if (spelling) intervals[ss] else 1,
            lastDay = day, meaningMisses = old.meaningMisses + if (meaning) 0 else 1,
            spellingMisses = old.spellingMisses + if (spelling) 0 else 1)
    }

    fun plan(words: List<WordCard>, reviews: List<ReviewState>, day: Long, settings: AppSettings): DailySession? {
        val states = reviews.associateBy { it.wordId }
        val ready = words.filter { it.complete() }
        val due = ready.filter { states[it.id]?.let { r -> r.started && r.due() <= day && r.lastDay != day } == true }
            .sortedBy { states[it.id]?.due() }.take(settings.reviewLimit.coerceIn(0, 200))
        val fresh = ready.filter { states[it.id]?.started != true }.sortedBy { it.createdAt }
            .take(settings.newLimit.coerceIn(0, 100))
        val items = due.map { QuizItem(it.id, false) } + fresh.map { QuizItem(it.id, true) }
        if (items.isEmpty()) return null
        return DailySession(day, codec.encodeToString(items), phase = if (items.first().newWord) "study" else "meaning")
    }

    fun meaningOptions(correct: String, candidates: List<String>): List<String> {
        fun normalized(s: String) = s.replace(Regex("[\\s\\p{Punct}，。；：]"), "")
        val normalizedCorrect = normalized(correct)
        val distractors = candidates.map { it.trim() }.filter { it.isNotBlank() && normalized(it) != normalizedCorrect }
            .filter { normalized(it).length > 1 && !normalizedCorrect.contains(normalized(it)) && !normalized(it).contains(normalizedCorrect) }
            .distinct().shuffled().take(3)
        require(distractors.size == 3) { "暂时无法生成足够的不同释义选项" }
        return (distractors + correct).shuffled()
    }

    fun diff(expected: String, actual: String): String {
        val e = wordKey(expected); val a = wordKey(actual)
        return e.indices.filter { it >= a.length || e[it] != a[it] }.map { it + 1 }.let {
            when { a == e -> "拼写正确"; a.length > e.length -> "有多余字母；请对照完整拼写"; else -> "请留意第 " + it.joinToString("、") + " 个字母" }
        }
    }
}
