package com.wordmate.app

import java.util.UUID

object StageRules {
    val kinds = listOf("image", "meaning", "listening", "spelling")
    fun key(word: String, kind: String) = word + ":" + kind
    fun passed(state: MixedSession, word: String, kind: String): Boolean = state.answers.any {
        it.wordId == word && it.kind == kind && it.correct && !it.assisted
    }
    fun enter(state: MixedSession, index: Int): MixedSession {
        for (stage in index until kinds.size) {
            val remaining = state.templates.filter { it.kind == kinds[stage] &&
                !passed(state,it.wordId,it.kind) && key(it.wordId,it.kind) !in state.deferred && it.wordId !in state.gradedWords }
            if (remaining.isNotEmpty()) {
                val questions = remaining.map { q ->
                    val attempted = state.answers.any { it.wordId == q.wordId && it.kind == q.kind }
                    q.copy(id=UUID.randomUUID().toString(),retry=attempted,originId=q.id,
                        choices=q.choices.shuffled(),pictures=q.pictures.shuffled())
                }
                val first = questions.first()
                return state.copy(stageIndex=stage,questions=questions,position=0,feedback=false,
                    assisted=first.kind=="spelling"&&first.wordId in state.legacySpellingAssisted,
                    round=if(stage==state.stageIndex) state.round else 1,draft=state.drafts[key(first.wordId,first.kind)].orEmpty())
            }
        }
        return state.copy(stageIndex=4,questions=emptyList(),position=0,feedback=false,draft="",assisted=false)
    }
    fun record(state: MixedSession, answer: ExerciseAnswer): MixedSession {
        val q=state.current()?:return state
        if(state.feedback || q.id!=answer.questionId || state.answers.any{it.questionId==q.id})return state
        // Preserve the first result and the most recent repeat; loops never grow the snapshot without bound.
        val kept=if(answer.retry)state.answers.filterNot{it.wordId==q.wordId&&it.kind==q.kind&&it.retry}else state.answers
        return state.copy(answers=kept+answer,feedback=true,draft=answer.value,
            drafts=state.drafts-key(q.wordId,q.kind),
            legacySpellingAssisted=if(q.kind=="spelling")state.legacySpellingAssisted-q.wordId else state.legacySpellingAssisted)
    }
    fun advance(state: MixedSession): MixedSession {
        if(!state.feedback)return state
        val next=state.position+1
        if(next<state.questions.size){
            val q=state.questions[next]
            return state.copy(position=next,feedback=false,
                assisted=q.kind=="spelling"&&q.wordId in state.legacySpellingAssisted,
                draft=state.drafts[key(q.wordId,q.kind)].orEmpty())
        }
        val repeat=state.questions.any{!passed(state,it.wordId,it.kind)&&key(it.wordId,it.kind) !in state.deferred}
        return enter(state.copy(round=if(repeat)state.round+1 else 1),state.stageIndex+if(repeat)0 else 1)
    }
    fun outcome(state: MixedSession, word: String): Pair<Boolean,Boolean>? {
        if(word in state.gradedWords)return null
        val required=state.templates.filter{it.wordId==word&&key(word,it.kind) !in state.deferred}
        if(required.none{it.kind=="spelling"} || required.any{!passed(state,word,it.kind)})return null
        fun first(kind:String)=state.answers.firstOrNull{it.wordId==word&&it.kind==kind&&!it.retry}
        val meaning=(state.partialMeaning[word]?:true)&&listOf("image","meaning").all{kind->
            required.none{it.kind==kind}||first(kind)?.correct==true
        }
        val spelling=listOf("listening","spelling").all{kind->required.none{it.kind==kind}||first(kind)?.let{it.correct&&!it.assisted}==true}
        return meaning to spelling
    }
}
