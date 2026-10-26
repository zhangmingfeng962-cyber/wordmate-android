package com.wordmate.app

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LearningRulesTest {
    private val day = 20000L
    private fun ready(word: String, created: Long = 1) = WordCard(word, word, meaning = "测试释义", exampleEn = "This is " + word + ".",
        exampleZh = "这是测试。", image = "media/test.jpg", createdAt = created)
    @Test fun emptyBookNeverImportsDictionaryWords() {
        assertNull(LearningRules.plan(emptyList(), emptyList(), day, AppSettings()))
    }
    @Test fun incompleteWordsNeverEnterPlan() {
        assertNull(LearningRules.plan(listOf(WordCard("apple","apple", meaning="苹果")), emptyList(), day, AppSettings()))
    }
    @Test fun firstPassSchedulesTomorrow() {
        val result = LearningRules.grade(ReviewState("apple"), day, true, true)
        assertEquals(day + 1, result.meaningDue); assertEquals(day + 1, result.spellingDue)
        assertTrue(result.started)
    }
    @Test fun meaningAndSpellingAdvanceIndependently() {
        val old = ReviewState("word", true, 2, 2, day, day, day - 1)
        val result = LearningRules.grade(old, day, true, false)
        assertEquals(day + 14, result.meaningDue); assertEquals(day + 1, result.spellingDue)
        assertEquals(1, result.spellingMisses); assertEquals(0, result.meaningMisses)
    }
    @Test fun earliestWeakSkillControlsDueDate() {
        assertEquals(day, ReviewState("a", true, meaningDue=day+20, spellingDue=day).due())
    }
    @Test fun retryDoesNotEraseFirstFailureOrAdvanceAgain() {
        val first = LearningRules.grade(ReviewState("apple"), day, false, false)
        assertEquals(first, LearningRules.grade(first, day, true, true))
    }
    @Test fun intervalsProgressAndCapAtSixtyDays() {
        var review = ReviewState("word")
        var currentDay = day
        for (interval in listOf(1L, 3L, 7L, 14L, 30L, 60L, 60L)) {
            review = LearningRules.grade(review, currentDay, true, true)
            assertEquals(currentDay + interval, review.meaningDue)
            currentDay = review.meaningDue
        }
    }
    @Test fun failureResetsOnlyFailedSkill() {
        val result = LearningRules.grade(ReviewState("a",true,5,4,day,day,day-5),day,false,true)
        assertEquals(0,result.meaningStep); assertEquals(5,result.spellingStep)
        assertEquals(day+1,result.meaningDue); assertEquals(day+60,result.spellingDue)
    }
    @Test fun overdueWordsComeBeforeUserAddedNewWords() {
        val words = listOf(ready("new"),ready("overdue"),ready("later"))
        val reviews = listOf(ReviewState("overdue",true,meaningDue=day-5,spellingDue=day+2),
            ReviewState("later",true,meaningDue=day+1,spellingDue=day+2))
        val plan = LearningRules.plan(words,reviews,day,AppSettings())!!
        assertEquals(listOf("overdue","new"),plan.queue().map{it.wordId})
        assertEquals("meaning",plan.phase)
    }
    @Test fun quotasAndCreatedOrderApplyToOwnWords() {
        val plan = LearningRules.plan(listOf(ready("third",3),ready("first",1),ready("second",2)),emptyList(),day,AppSettings(newLimit=2))!!
        assertEquals(listOf("first","second"),plan.queue().map{it.wordId})
        assertEquals("study",plan.phase)
    }
    @Test fun completedWordsDoNotReenterSameDayPlan() {
        val reviews = listOf(ReviewState("apple",true,meaningDue=day-1,spellingDue=day-1,lastDay=day))
        assertNull(LearningRules.plan(listOf(ready("apple")),reviews,day,AppSettings()))
    }
    @Test fun spellingIgnoresCaseAndOuterWhitespace() {
        assertTrue(LearningRules.spellingCorrect("  BeAuTiFuL \n","beautiful"))
    }
    @Test fun spellingKeepsHyphensApostrophesAndInternalSpaces() {
        assertFalse(LearningRules.spellingCorrect("cant","can't"))
        assertFalse(LearningRules.spellingCorrect("motherinlaw","mother-in-law"))
        assertFalse(LearningRules.spellingCorrect("beau tiful","beautiful"))
    }
    @Test fun segmentsMustExactlyRebuildWord() {
        assertTrue(LearningRules.validSegments("beautiful",listOf("beau","ti","ful")))
        assertFalse(LearningRules.validSegments("beautiful",listOf("beau","ty","ful")))
        assertFalse(LearningRules.validSegments("Beautiful",listOf("beau","ti","ful")))
        assertFalse(LearningRules.validSegments("word",listOf("","word")))
    }
    @Test fun oneWordBookStillHasFourMeaningChoices() {
        val choices = LearningRules.meaningOptions("苹果",listOf("苹果","道路","窗户","勇敢","苹果"))
        assertEquals(4,choices.size); assertEquals(4,choices.distinct().size); assertTrue("苹果" in choices)
    }
    @Test fun meaningChoicesRejectEquivalentOrContainedLabels() {
        val choices=LearningRules.meaningOptions("n. 苹果",listOf("n.苹果","苹果","道路","窗户","勇敢","安静"))
        assertEquals(1,choices.count{it.contains("苹果")})
    }
    @Test fun savedSessionKeepsQueueTypedAnswerAndHint() {
        val state = DailySession(day,codec.encodeToString(listOf(QuizItem("apple",true))),phase="spelling",
            typed="appl",assisted=true,options=codec.encodeToString(listOf("苹果","道路","窗户","勇敢")))
        assertEquals(state,codec.decodeFromString<DailySession>(codec.encodeToString(state)))
    }
    @Test fun wordValidationAllowsCommonPunctuationButNotSentences() {
        assertTrue(validWord("can't")); assertTrue(validWord("mother-in-law"))
        assertFalse(validWord("two words")); assertFalse(validWord("")); assertFalse(validWord("<script>"))
    }
    @Test fun ocrCandidatesAreUniqueEditableWordsNotAutomaticImports() {
        assertEquals(listOf("apple","beautiful","can't"),OcrService.extract("apple 苹果 APPLE\nbeautiful can't"))
    }
}
