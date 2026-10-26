package com.wordmate.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Entity(tableName = "enrichment_jobs")
data class EnrichmentJob(
    @PrimaryKey val wordId: String,
    val state: String = "pending",
    val stage: String = "等待处理",
    val error: String = "",
    val generation: String = UUID.randomUUID().toString(),
    val contextRevision: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "mixed_sessions")
data class MixedSessionRecord(@PrimaryKey val day: Long, val payload: String)

@Serializable data class ImageOption(val id: String, val image: String)
@Serializable data class ExerciseQuestion(
    val id: String = UUID.randomUUID().toString(),
    val wordId: String,
    val kind: String,
    val choices: List<String> = emptyList(),
    val pictures: List<ImageOption> = emptyList(),
    val newWord: Boolean = false,
    val retry: Boolean = false,
    val originId: String = ""
)
@Serializable data class ExerciseAnswer(
    val questionId: String, val wordId: String, val kind: String,
    val correct: Boolean, val assisted: Boolean = false, val retry: Boolean = false,
    val value: String = ""
)
@Serializable data class MixedSession(
    val day: Long,
    val groups: List<List<QuizItem>>,
    val groupIndex: Int = 0,
    val questions: List<ExerciseQuestion> = emptyList(),
    val position: Int = 0,
    val feedback: Boolean = false,
    val cards: Map<String, WordCard> = emptyMap(),
    val answers: List<ExerciseAnswer> = emptyList(),
    val completedWords: List<String> = emptyList(),
    val correctWords: List<String> = emptyList(),
    val gradedWords: List<String> = emptyList(),
    val partialMeaning: Map<String, Boolean> = emptyMap(),
    val legacyCompleted: Int = 0,
    val legacyCorrect: Int = 0,
    val draft: String = "",
    val assisted: Boolean = false,
    val finished: Boolean = false,
    val version: Int = 2,
    val dailyNewLimit: Int = 10,
    val dailyReviewLimit: Int = 20,
    val enrolledNew: Int = 0,
    val enrolledReview: Int = 0,
    val stageIndex: Int = 0,
    val round: Int = 1,
    val templates: List<ExerciseQuestion> = emptyList(),
    val drafts: Map<String, String> = emptyMap(),
    val deferred: List<String> = emptyList(),
    val legacySpellingAssisted: List<String> = emptyList()
) {
    fun current() = if (finished) null else questions.getOrNull(position)
    fun completed() = legacyCompleted + completedWords.size
    fun correct() = legacyCorrect + correctWords.size
    fun summary() = DailySession(day, "[]", phase = if (finished) "done" else "meaning", completed = completed(), correct = correct())
}

object MixedRules {
    fun additional(state: MixedSession, words: List<WordCard>, reviews: List<ReviewState>): List<QuizItem> {
        val enrolled = state.groups.flatten()
        val existing = enrolled.map { it.wordId }.toSet()
        val newCount = maxOf(state.enrolledNew, enrolled.filter { it.newWord && !it.retry }.map { it.wordId }.distinct().size)
        val reviewCount = maxOf(state.enrolledReview, enrolled.filter { !it.newWord && !it.retry }.map { it.wordId }.distinct().size)
        return select(words.filter { it.id !in existing }, reviews, state.day, AppSettings(
            newLimit = (state.dailyNewLimit - newCount).coerceAtLeast(0),
            reviewLimit = (state.dailyReviewLimit - reviewCount).coerceAtLeast(0)))
    }
    fun select(words: List<WordCard>, reviews: List<ReviewState>, day: Long, settings: AppSettings): List<QuizItem> {
        val state = reviews.associateBy { it.wordId }
        val ready = words.filter { it.textReady() }
        val due = ready.filter { state[it.id]?.let { r -> r.started && r.due() <= day && r.lastDay != day } == true }
            .sortedBy { state[it.id]?.due() }.take(settings.reviewLimit.coerceIn(0, 200))
        val fresh = ready.filter { state[it.id]?.started != true }.sortedBy { it.createdAt }.take(settings.newLimit.coerceIn(0, 100))
        return due.map { QuizItem(it.id, false) } + fresh.map { QuizItem(it.id, true) }
    }
    fun record(state: MixedSession, answer: ExerciseAnswer): MixedSession {
        if (state.version >= 3) return StageRules.record(state, answer)
        val question = state.current() ?: return state
        if (state.feedback || state.answers.any { it.questionId == question.id } || answer.questionId != question.id) return state
        var questions = state.questions
        if (!answer.correct && !question.retry) {
            val future = questions.drop(state.position + 1)
            val distinct = mutableSetOf<String>()
            var insertion = questions.size
            for ((offset, other) in future.withIndex()) {
                if (other.wordId != question.wordId) distinct += other.wordId
                if (distinct.size >= 2) { insertion = state.position + offset + 2; break }
            }
            val retry = question.copy(id = UUID.randomUUID().toString(), retry = true, originId = question.id,
                choices = question.choices.shuffled(), pictures = question.pictures.shuffled())
            questions = questions.toMutableList().apply { add(insertion, retry) }
        }
        return state.copy(questions = questions, answers = state.answers + answer, feedback = true, draft = answer.value)
    }
    fun outcome(state: MixedSession, wordId: String): Pair<Boolean, Boolean>? {
        if (state.version >= 3) return StageRules.outcome(state, wordId)
        if (wordId in state.gradedWords) return null
        val first = state.questions.filter { it.wordId == wordId && !it.retry }
        if (first.isEmpty() || first.none { it.kind == "spelling" }) return null
        val answered = state.answers.filter { it.wordId == wordId && !it.retry }
        if (!first.all { q -> answered.any { it.questionId == q.id } }) return null
        val meaning = (state.partialMeaning[wordId] ?: true) && answered.filter { it.kind != "spelling" }.all { it.correct }
        val spelling = answered.firstOrNull { it.kind == "spelling" }?.let { it.correct && !it.assisted } ?: return null
        return meaning to spelling
    }
    fun showCard(state: MixedSession): Boolean {
        if (state.version >= 3) return state.feedback
        val current = state.current() ?: return false
        val answer = state.answers.lastOrNull { it.questionId == current.id } ?: return false
        return !answer.correct || (current.newWord && state.answers.count { it.wordId == current.wordId } == 1)
    }
}
