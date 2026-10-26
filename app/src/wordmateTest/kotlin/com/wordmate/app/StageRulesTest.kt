package com.wordmate.app

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class StageRulesTest {
    private fun state(words:List<String> = listOf("apple","book")):MixedSession {
        val templates=StageRules.kinds.flatMap{kind->words.map{ExerciseQuestion(wordId=it,kind=kind)}}
        return StageRules.enter(MixedSession(24000,listOf(words.map{QuizItem(it,true)}),version=3,templates=templates),0)
    }
    private fun answer(state:MixedSession,correct:Boolean=true,assisted:Boolean=false):MixedSession {
        val q=state.current()!!
        return StageRules.record(state,ExerciseAnswer(q.id,q.wordId,q.kind,correct,assisted,q.retry,"test"))
    }
    @Test fun groupIsFourOrderedStagesAndFeedbackAlwaysShowsCard(){
        var s=state()
        for(kind in StageRules.kinds){
            assertEquals(kind,s.current()!!.kind)
            repeat(2){s=answer(s);assertTrue(MixedRules.showCard(s));assertTrue(s.feedback);s=StageRules.advance(s)}
        }
        assertEquals(4,s.stageIndex);assertNull(s.current())
    }
    @Test fun onlyFailedWordsRepeatBeforeNextStage(){
        var s=state(listOf("apple","book","car"))
        s=StageRules.advance(answer(s,false));s=StageRules.advance(answer(s));s=StageRules.advance(answer(s,false))
        assertEquals("image",s.current()!!.kind);assertEquals(2,s.round)
        assertEquals(listOf("apple","car"),s.questions.map{it.wordId})
        s=StageRules.advance(answer(s));s=StageRules.advance(answer(s,false))
        assertEquals(listOf("car"),s.questions.map{it.wordId});assertEquals(3,s.round)
        s=StageRules.advance(answer(s));assertEquals("meaning",s.current()!!.kind);assertEquals(1,s.round)
    }
    @Test fun anyNumberOfFailuresCanBeRetriedWithBoundedSnapshot(){
        var s=state(listOf("apple"))
        repeat(200){s=StageRules.advance(answer(s,false))}
        assertEquals("image",s.current()!!.kind);assertEquals(201,s.round)
        assertTrue(s.answers.size<=2);assertEquals(1,s.questions.size)
        s=StageRules.advance(answer(s));assertEquals("meaning",s.current()!!.kind)
        assertFalse(s.answers.first().correct)
    }
    @Test fun feedbackRequiresExplicitContinueAndCannotBeSubmittedTwice(){
        val s=state();assertEquals(s,StageRules.advance(s))
        val a=answer(s);assertEquals(a,answer(a,false));assertEquals(s.current(),a.current())
    }
    @Test fun hintDoesNotPassStageUntilUnassistedAnswer(){
        val initial=state(listOf("apple")).let{StageRules.enter(it,3)}
        var s=StageRules.advance(answer(initial,false,true))
        assertEquals("spelling",s.current()!!.kind);assertEquals(2,s.round)
        s=StageRules.advance(answer(s));assertEquals(4,s.stageIndex)
    }
    @Test fun listeningFailureWeakensSpellingButNotMeaning(){
        var s=state(listOf("apple"))
        repeat(2){s=StageRules.advance(answer(s))}
        s=StageRules.advance(answer(s,false));s=StageRules.advance(answer(s))
        s=answer(s)
        assertEquals(true to false,StageRules.outcome(s,"apple"))
        assertNull(StageRules.outcome(s.copy(gradedWords=listOf("apple")),"apple"))
    }
    @Test fun imageFailureRemainsInFirstResultAfterPassingRetake(){
        var s=state(listOf("apple"));s=StageRules.advance(answer(s,false));s=StageRules.advance(answer(s))
        repeat(2){s=StageRules.advance(answer(s))};s=answer(s)
        assertEquals(false to true,StageRules.outcome(s,"apple"))
    }
    @Test fun serializationPreservesRoundFeedbackAndDraft(){
        var s=state();s=StageRules.advance(answer(s,false));s=answer(s)
        val restored=codec.decodeFromString<MixedSession>(codec.encodeToString(s.copy(drafts=mapOf("apple:spelling" to "app"))))
        assertTrue(restored.feedback);assertEquals(s.current(),restored.current());assertEquals("app",restored.drafts["apple:spelling"])
    }
    @Test fun deferredResourceDoesNotBecomeWrongAnswer(){
        val s=state(listOf("apple"))
        val next=StageRules.advance(s.copy(feedback=true,deferred=listOf("apple:image")))
        assertEquals("meaning",next.current()!!.kind);assertTrue(next.answers.isEmpty())
    }
    @Test fun dictionaryRecordingMustMatchExactFormAndAccent(){
        assertTrue(RecordingRules.candidate("apple","apple","https://api.dictionaryapi.dev/media/pronunciations/en/apple-us.mp3","en-US"))
        assertFalse(RecordingRules.candidate("samples","sample","https://api.dictionaryapi.dev/media/pronunciations/en/sample-us.mp3","en-US"))
        assertFalse(RecordingRules.candidate("apple","apple","https://api.dictionaryapi.dev/media/pronunciations/en/apple-uk.mp3","en-US"))
        assertFalse(RecordingRules.candidate("apple","apple","https://untrusted.example/apple-us.mp3","en-US"))
    }
    @Test fun syntheticOrUnlicensedAudioCannotBeLabelledHuman(){
        val license="https://creativecommons.org/licenses/by-sa/3.0/"
        assertTrue(RecordingRules.verified("apple","US pronunciation of apple, recorded by a speaker","Speaker",license))
        assertFalse(RecordingRules.verified("apple","US pronunciation of apple, synthesized","Speaker",license))
        assertFalse(RecordingRules.verified("apple","US pronunciation of apple","",""))
    }
}
