package com.wordmate.app

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

private fun MixedSession.record() = MixedSessionRecord(day, codec.encodeToString(this))
fun MixedSessionRecord.decode(): MixedSession = codec.decodeFromString(payload)

private suspend fun WordRepository.extend(state: MixedSession): MixedSession {
    if (!state.finished) return state
    val additions = MixedRules.additional(state, dao.words(), dao.reviews())
    if (additions.isEmpty()) return state
    val expanded = state.copy(groups = state.groups + additions.chunked(5), finished = false,
        enrolledNew = maxOf(state.enrolledNew, state.groups.flatten().filter { it.newWord && !it.retry }.map { it.wordId }.distinct().size) + additions.count { it.newWord },
        enrolledReview = maxOf(state.enrolledReview, state.groups.flatten().filter { !it.newWord && !it.retry }.map { it.wordId }.distinct().size) + additions.count { !it.newWord })
    return group(expanded, state.groups.size)
}

private suspend fun WordRepository.group(state: MixedSession, index: Int): MixedSession {
    var groupIndex = index
    while (groupIndex < state.groups.size) {
        val items = state.groups[groupIndex]
        val available = dao.words()
        val cards = items.mapNotNull { item -> available.firstOrNull { it.id == item.wordId && it.textReady() } }.associateBy { it.id }
        val picturesRound = mutableListOf<ExerciseQuestion>()
        val meaningsRound = mutableListOf<ExerciseQuestion>()
        val listeningRound = mutableListOf<ExerciseQuestion>()
        val spellingRound = mutableListOf<ExerciseQuestion>()
        val candidates = dictionary.distractors()
        for (item in items) {
            val card = cards[item.wordId] ?: continue
            if(card.id in state.gradedWords)continue
            if (item.wordId !in state.partialMeaning) {
                if (!item.retry) {
                    val choices = pictures.choices(card, available)
                    if (choices.size == 4) picturesRound += ExerciseQuestion(wordId = card.id, kind = "image", pictures = choices, newWord = item.newWord)
                }
                meaningsRound += ExerciseQuestion(wordId = card.id, kind = "meaning",
                    choices = LearningRules.meaningOptions(card.meaning, candidates), newWord = item.newWord, retry = item.retry)
            }
            val englishOptions=dictionary.wordOptions(card,available)
            if(englishOptions.size==4)listeningRound+=ExerciseQuestion(wordId=card.id,kind="listening",choices=englishOptions,newWord=item.newWord)
            spellingRound += ExerciseQuestion(wordId = card.id, kind = "spelling", newWord = item.newWord, retry = item.retry)
        }
        val questions = picturesRound + meaningsRound + listeningRound + spellingRound
        if (questions.isNotEmpty()) return StageRules.enter(state.copy(version=3,stageIndex=0,round=1,templates=questions,groupIndex = groupIndex, questions = questions, position = 0,
            cards = state.cards + cards.mapValues { (_, c) -> c.copy(sources = "", imageCredit = "", error = "") },
            feedback = false, draft = "", assisted = false),0)
        groupIndex++
    }
    return state.copy(finished = true, groupIndex = state.groups.size, questions = emptyList(), position = 0, feedback = false)
}

suspend fun WordRepository.startMixed(day: Long): MixedSession? = mixedLock.withLock {
    dao.mixed(day)?.let { record ->
        var saved = record.decode()
        if(saved.version<3&&!saved.finished){
            val old=saved
            val drafts=old.current()?.takeIf{it.kind=="spelling"}?.let{mapOf(StageRules.key(it.wordId,it.kind) to old.draft)}.orEmpty()
            val hinted=old.current()?.takeIf{it.kind=="spelling"&&old.assisted}?.let{listOf(it.wordId)}.orEmpty()
            saved=group(old.copy(version=3,drafts=drafts,legacySpellingAssisted=hinted),old.groupIndex)
        }
        if(saved.version>=3){
            val existing=dao.words().map{it.id}.toSet()
            val current=saved.current()
            val live=saved.questions.filter{it.wordId in existing}
            val position=live.indexOfFirst{it.id==current?.id}.takeIf{it>=0}
                ?:saved.questions.take(saved.position).count{it.wordId in existing}
            saved=saved.copy(questions=live,position=position,feedback=saved.feedback&&current?.wordId in existing,
                templates=saved.templates.filter{it.wordId in existing})
            if(!saved.finished&&saved.current()==null){saved=StageRules.enter(saved.copy(position=0,feedback=false),saved.stageIndex)
                if(saved.stageIndex>=4)saved=group(saved,saved.groupIndex+1)}
        }
        while (saved.current() != null && dao.word(saved.current()!!.wordId) == null) {
            val next = saved.position + 1
            saved = if (next < saved.questions.size) saved.copy(position = next, feedback = false, draft = "", assisted = false)
                else group(saved, saved.groupIndex + 1)
        }
        saved = extend(saved)
        if (saved != record.decode()) dao.putMixed(saved.record())
        return@withLock saved
    }
    val legacy = dao.session(day)
    val selected: List<QuizItem>
    var state: MixedSession
    val prefs = settings.get()
    if (legacy != null) {
        if (legacy.phase == "done") {
            state = MixedSession(day, emptyList(), finished = true, legacyCompleted = legacy.completed, legacyCorrect = legacy.correct,
                dailyNewLimit = prefs.newLimit, dailyReviewLimit = prefs.reviewLimit,
                enrolledNew = legacy.queue().filter { !it.retry }.count { it.newWord },
                enrolledReview = legacy.queue().filter { !it.retry }.count { !it.newWord })
            dao.putMixed(state.record()); return@withLock state
        }
        val start = legacy.index + if (legacy.phase == "reveal") 1 else 0
        selected = legacy.queue().drop(start)
        val partial = if (legacy.phase == "spelling") legacy.current()?.let { mapOf(it.wordId to legacy.meaningCorrect) }.orEmpty() else emptyMap()
        state = MixedSession(day, selected.chunked(5), version=3, partialMeaning = partial, legacyCompleted = legacy.completed, legacyCorrect = legacy.correct,
            legacySpellingAssisted=if(legacy.phase=="spelling"&&legacy.assisted)legacy.current()?.let{listOf(it.wordId)}.orEmpty()else emptyList(),
            drafts=if(legacy.phase=="spelling")legacy.current()?.let{mapOf(StageRules.key(it.wordId,"spelling") to legacy.typed)}.orEmpty()else emptyMap(),
            draft = if (legacy.phase == "spelling") legacy.typed else "", assisted = legacy.assisted,
            dailyNewLimit = prefs.newLimit, dailyReviewLimit = prefs.reviewLimit,
            enrolledNew = legacy.queue().filter { !it.retry }.count { it.newWord },
            enrolledReview = legacy.queue().filter { !it.retry }.count { !it.newWord })
    } else {
        selected = MixedRules.select(dao.words(), dao.reviews(), day, settings.get())
        if (selected.isEmpty()) return@withLock null
        state = MixedSession(day, selected.chunked(5), version=3, dailyNewLimit = prefs.newLimit, dailyReviewLimit = prefs.reviewLimit,
            enrolledNew = selected.count { it.newWord }, enrolledReview = selected.count { !it.newWord })
    }
    val initialDraft = state.draft
    val initialHint = state.assisted
    state = group(state, 0)
    // A v1 half-finished word continues its spelling question before starting other rounds.
    if (state.version<3&&state.partialMeaning.isNotEmpty()) {
        val currentWord = state.partialMeaning.keys.first()
        val current = state.questions.firstOrNull { it.wordId == currentWord && it.kind == "spelling" }
        if (current != null) state = state.copy(questions = listOf(current) + state.questions.filter { it.id != current.id },
            draft = initialDraft, assisted = initialHint)
    }
    dao.putMixed(state.record())
    state
}

suspend fun WordRepository.answerMixed(day: Long, questionId: String, value: String, forgot: Boolean = false) = mixedLock.withLock {
    database.withTransaction {
        val state = dao.mixed(day)?.decode() ?: return@withTransaction
        val q = state.current() ?: return@withTransaction
        if (q.id != questionId || state.feedback) return@withTransaction
        if (dao.word(q.wordId) == null) return@withTransaction
        val card = state.cards[q.wordId] ?: return@withTransaction
        val correct = !forgot && when(q.kind) {
            "image" -> {
                require(value in q.pictures.map { it.id })
                value == "word:" + q.wordId
            }
            "meaning" -> { require(value in q.choices); value == card.meaning }
            "listening" -> {require(value in q.choices); LearningRules.spellingCorrect(value,card.spelling)}
            "spelling" -> !state.assisted && LearningRules.spellingCorrect(value, card.spelling)
            else -> false
        }
        val answer = ExerciseAnswer(q.id, q.wordId, q.kind, correct, q.kind == "spelling" && state.assisted, q.retry, value.take(1000))
        var next = MixedRules.record(state, answer)
        val result = MixedRules.outcome(next, q.wordId)
        if (result != null) {
            val old = dao.review(q.wordId) ?: ReviewState(q.wordId)
            if (old.lastDay != day) {
                dao.putReview(LearningRules.grade(old, day, result.first, result.second))
                val spelling = next.answers.first { it.wordId == q.wordId && it.kind == "spelling" && !it.retry }
                dao.putAttempt(ReviewAttempt("mixed:" + day + ":" + q.wordId, q.wordId, day, result.first, result.second,
                    false, spelling.assisted, spelling.value))
                next = next.copy(completedWords = next.completedWords + q.wordId,
                    correctWords = if (result.first && result.second) next.correctWords + q.wordId else next.correctWords)
            }
            next = next.copy(gradedWords = next.gradedWords + q.wordId)
        }
        dao.putMixed(next.record())
    }
}
suspend fun WordRepository.nextMixed(day: Long, questionId: String) = mixedLock.withLock {
    var state = dao.mixed(day)?.decode() ?: return@withLock
    if (state.current()?.id != questionId) return@withLock
    if(!state.feedback)return@withLock
    if(state.version>=3){
        val existing=dao.words().map{it.id}.toSet()
        state=StageRules.advance(state.copy(templates=state.templates.filter{it.wordId in existing}))
        while(state.current()!=null&&state.current()!!.wordId !in existing){
            state=StageRules.advance(state.copy(feedback=true))
        }
        if(state.stageIndex>=4)state=group(state,state.groupIndex+1)
        state=extend(state);dao.putMixed(state.record());return@withLock
    }
    var index = state.position + 1
    while (index < state.questions.size && dao.word(state.questions[index].wordId) == null) index++
    state = if (index < state.questions.size) state.copy(position = index, feedback = false, draft = "", assisted = false)
        else group(state, state.groupIndex + 1)
    state = extend(state)
    dao.putMixed(state.record())
}
suspend fun WordRepository.draftMixed(day: Long, questionId: String, text: String) = mixedLock.withLock {
    val state = dao.mixed(day)?.decode() ?: return@withLock
    if (state.current()?.id == questionId && state.current()?.kind == "spelling" && !state.feedback)
        dao.putMixed(state.copy(draft = text.take(100),drafts=state.drafts+(StageRules.key(state.current()!!.wordId,"spelling") to text.take(100))).record())
}
suspend fun WordRepository.hintMixed(day: Long, questionId: String) = mixedLock.withLock {
    val state = dao.mixed(day)?.decode() ?: return@withLock
    if (state.current()?.id == questionId && state.current()?.kind == "spelling" && !state.feedback)
        dao.putMixed(state.copy(assisted = true).record())
}
suspend fun WordRepository.skipBrokenPicture(day: Long, questionId: String) = mixedLock.withLock {
    var state = dao.mixed(day)?.decode() ?: return@withLock
    val current = state.current() ?: return@withLock
    if (current.id != questionId || current.kind != "image" || state.feedback) return@withLock
    val questions = state.questions.filter { it.id != questionId }
    if(state.version>=3){
        val deferred=state.deferred+StageRules.key(current.wordId,"image")
        state=state.copy(deferred=deferred,feedback=true)
        state=StageRules.advance(state)
        if(state.stageIndex>=4)state=group(state,state.groupIndex+1)
        dao.putMixed(state.record());return@withLock
    }
    state = state.copy(questions = questions, feedback = false, draft = "", assisted = false)
    if (state.position >= questions.size) state = group(state, state.groupIndex + 1)
    dao.putMixed(state.record())
}
