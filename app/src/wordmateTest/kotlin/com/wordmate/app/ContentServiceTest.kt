package com.wordmate.app

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
class ContentServiceTest {
    @Test fun transportLimitsActualConcurrentRequestsToFour() = runBlocking {
        val server=MockWebServer()
        val entered=CountDownLatch(4)
        val release=CountDownLatch(1)
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse{
                entered.countDown();release.await(5,TimeUnit.SECONDS)
                return MockResponse().setBody("ok")
            }
        }
        server.start()
        try{
            val transport=HttpTransport()
            val requests=(1..5).map{async(Dispatchers.Default){transport.bytes(Request.Builder().url(server.url("/"+it)).build())}}
            assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)})
            assertEquals(4,server.requestCount)
            release.countDown();requests.awaitAll()
            assertEquals(5,server.requestCount)
        }finally{release.countDown();server.shutdown()}
        Unit
    }
    @Test fun transportHonorsPerStageDeadline() = runBlocking {
        val server=MockWebServer();server.start()
        try{
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(700,TimeUnit.MILLISECONDS))
            assertTrue(runCatching{HttpTransport().bytes(Request.Builder().url(server.url("/")).build(),timeoutMs=100)}.isFailure)
        }finally{server.shutdown()}
        Unit
    }
    @Test fun baseUrlSupportsVersionedAndFullEndpoint() {
        assertEquals("https://api.example.com/v1/chat/completions",ContentService.endpoint("https://api.example.com/v1/","chat/completions"))
        assertEquals("https://api.example.com/v1/models",ContentService.endpoint("https://api.example.com/v1/images/generations","models"))
    }
    @Test fun endpointRejectsCleartextAndCredentials() {
        for (base in listOf("http://example.com/v1","https://secret@example.com/v1","https://example.com/v1?key=secret"))
            assertTrue(runCatching{ContentService.endpoint(base,"models")}.isFailure)
    }
    @Test fun aiMergePreservesManualFieldsAndRejectsBadSplit() {
        val card=WordCard("beautiful","beautiful",meaning="手动词义",exampleEn="This is beautiful.",exampleZh="手写译文",segments=codec.encodeToString(listOf("beau","ti","ful")))
        val payload=JSONObject().put("meaningZh","新词义").put("exampleEn","A beautiful day.").put("exampleZh","好天气")
            .put("segments",JSONArray(listOf("wrong","split")))
        val merged=ContentParsing.mergeAi(card,payload)
        assertEquals(card.meaning,merged.meaning);assertEquals(card.exampleEn,merged.exampleEn)
        assertEquals(card.exampleZh,merged.exampleZh);assertEquals(card.segments,merged.segments)
    }
    @Test fun aiDoesNotAttachUnrelatedTranslationToManualSentence() {
        val card=WordCard("apple","apple",exampleEn="I have an apple.")
        val payload=JSONObject().put("exampleEn","An apple fell.").put("exampleZh","苹果掉了。")
        assertTrue(ContentParsing.mergeAi(card,payload).exampleZh.isBlank())
    }
    @Test fun aiCompletesMissingFieldsAndRejectsExampleWithoutWord() {
        val payload=JSONObject().put("meaningZh","苹果").put("meaningsZh",JSONArray(listOf("苹果")))
            .put("exampleEn","This is a banana.").put("exampleZh","这是一根香蕉。")
            .put("segments",JSONArray(listOf("ap","ple")))
        val merged=ContentParsing.mergeAi(WordCard("apple","apple"),payload)
        assertEquals("苹果",merged.meaning);assertTrue(merged.exampleEn.isEmpty());assertTrue(merged.exampleZh.isEmpty())
        assertEquals(listOf("ap","ple"),merged.chunks())
    }
    @Test fun fencedModelJsonIsParsedWithoutExecutingAnything() {
        val response=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","Response:\n"+JSONObject().put("meaningZh","苹果").toString()))))
        assertEquals("苹果",ContentParsing.aiText(response).getString("meaningZh"))
    }
    @Test fun exactWordRequiredInExample() {
        assertTrue(ContentParsing.hasWord("This apple is red.","apple"))
        assertFalse(ContentParsing.hasWord("These apples are red.","apple"))
    }
    @Test fun unauthorizedErrorDoesNotExposeServerBodyOrApiKey() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret-token-do-not-show"))
            val error=runCatching{HttpTransport().json(Request.Builder().url(server.url("/")).build())}.exceptionOrNull()!!
            assertTrue(error.message!!.contains("密钥"));assertFalse(error.message!!.contains("secret-token"))
        } finally{server.shutdown()}
    }
    @Test fun malformedServiceResponseFailsWithoutLosingExistingCard() {
        val card=WordCard("apple","apple",meaning="苹果")
        assertTrue(runCatching{ContentParsing.aiText(JSONObject())}.isFailure)
        assertEquals("苹果",card.meaning)
    }
    @Test fun transportAcceptsJsonAndEnforcesBodyLimit() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(HttpTransport().json(Request.Builder().url(server.url("/")).build()).getBoolean("ok"))
            server.enqueue(MockResponse().setBody("0123456789"))
            assertTrue(runCatching{HttpTransport().bytes(Request.Builder().url(server.url("/")).build(),5)}.isFailure)
        }finally{server.shutdown()}
    }
}
