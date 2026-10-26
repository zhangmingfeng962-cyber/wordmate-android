package com.wordmate.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=WordMateApp::class)
class ApiCompatibilityTest {
    private lateinit var repo: WordRepository
    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WordMateApp>()
        app.deleteDatabase("wordmate.db")
        repo = WordRepository(app)
        repo.settings.save(AppSettings())
    }
    @After fun close() { repo.database.close() }

    @Test fun stageReadTimeoutOverridesShortClientReadTimeout() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("ok").setBodyDelay(180,TimeUnit.MILLISECONDS))
            val client = OkHttpClient.Builder().readTimeout(40,TimeUnit.MILLISECONDS).build()
            assertEquals("ok",String(HttpTransport(client).bytes(Request.Builder().url(server.url("/")).build(),timeoutMs=800)))
        } finally { server.shutdown() }
        Unit
    }
    @Test fun actualOverdueResponseRetainsCodeAndRedactsCredential() = runBlocking {
        val server = MockWebServer(); server.start()
        val secret = "ark-unit-test-placeholder-123456789"
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody(JSONObject().put("error",JSONObject()
                .put("code","AccountOverdueError").put("message","overdue " + secret)).toString()))
            val failure = runCatching { HttpTransport().bytes(Request.Builder().url(server.url("/")).header("Authorization","Bearer "+secret).build()) }.exceptionOrNull() as ApiFailure
            assertTrue(failure.accountBlocked()); assertEquals(403,failure.status)
            assertEquals("AccountOverdueError",failure.code); assertTrue(failure.message!!.contains("欠费"))
            assertFalse(failure.message!!.contains(secret))
        } finally { server.shutdown() }
        Unit
    }
    @Test fun otherErrorsRemainDistinctAndRedacted() {
        val secret = "custom-private-value"
        val failure = ApiErrors.service(400,JSONObject().put("error",JSONObject().put("code","InvalidParameter")
            .put("message","bad " + secret + " Bearer hidden-example-token")).toString(),secret)
        assertFalse(failure.accountBlocked()); assertTrue(failure.message!!.contains("参数"))
        assertFalse(failure.message!!.contains(secret)); assertFalse(failure.message!!.contains("hidden-example-token"))
        assertTrue(ApiErrors.service(404,"{}").message!!.contains("不存在"))
        assertTrue(ApiErrors.service(429,"{}").message!!.contains("请求过多"))
    }
    @Test fun arkSeedTextDisablesThinkingAndRequestsJsonOnlyForArk() {
        val model = "doubao-seed-2-1-lite-260915"
        val body = ProviderRequests.text("https://ark.cn-beijing.volces.com/api/v3",model,JSONArray())
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object",body.getJSONObject("response_format").getString("type"))
        assertFalse(body.getBoolean("stream"))
        val generic = ProviderRequests.text("https://example.org/v1",model,JSONArray())
        assertFalse(generic.has("thinking")); assertFalse(generic.has("response_format"))
        assertFalse(ProviderRequests.isArk("https://volces.com.example.org/v1"))
    }
    @Test fun seedreamPresetUsesOneKJpegAndGenericKeepsStandardParams() {
        val body = ProviderRequests.image("https://ark.cn-beijing.volces.com/api/v3","doubao-seedream-5-0-flash-260915","scene")
        assertEquals("1K",body.getString("size")); assertEquals("jpeg",body.getString("output_format"))
        assertEquals("url",body.getString("response_format")); assertFalse(body.has("n"))
        assertEquals("2K",ProviderRequests.image("https://ark.cn-beijing.volces.com/api/v3","doubao-seedream-4-5-251128","scene").getString("size"))
        assertFalse(ProviderRequests.image("https://ark.cn-beijing.volces.com/api/v3","doubao-seedream-4-5-251128","scene").has("output_format"))
        assertEquals(1,ProviderRequests.image("https://example.org/v1","model","scene").getInt("n"))
    }
    @Test fun structuredContentAndSnakeCasePreserveExactSpelling() {
        val raw = JSONObject().put("meaning_zh","美丽的").put("example_en","A beautiful day.").put("example_zh","美好的一天。")
            .put("chunks","Beau-ti-ful").put("phonetic",JSONObject.NULL)
        val response = JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",JSONArray()
            .put(JSONObject().put("type","text").put("text","```json\n"+raw+"\n```"))))))
        val card = ContentParsing.mergeAi(WordCard("beautiful","beautiful"),ContentParsing.aiText(response))
        assertEquals("美丽的",card.meaning); assertEquals(listOf("beau","ti","ful"),card.chunks())
        assertEquals("美好的一天。",card.exampleZh); assertTrue(card.phonetic.isBlank())
    }
    @Test fun balancedJsonAllowsBracesInsideQuotedExample() {
        val raw = JSONObject().put("meaningZh","样品").put("exampleEn","These samples have {labels}.")
        val response = JSONObject().put("output_text","Result:\n"+raw+"\nextra")
        assertEquals("样品",ContentParsing.aiText(response).getString("meaningZh"))
    }
    @Test fun nullRefusalIsAcceptedButRealRefusalIsReported() {
        val message = JSONObject().put("refusal",JSONObject.NULL).put("content","{\"meaningZh\":\"苹果\"}")
        val response = JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message)))
        assertEquals("苹果",ContentParsing.aiText(response).getString("meaningZh"))
        message.put("refusal","I cannot do this")
        assertEquals("ModelRefusal",(runCatching { ContentParsing.aiText(response) }.exceptionOrNull() as ApiFailure).code)
    }
    @Test fun inflectedFormsResolveLocallyWithoutChangingSpellingOrBaseIpa() = runBlocking {
        val samples = repo.dictionary.lookup("samples")!!
        assertEquals("sample",samples.lemma); assertTrue(samples.meanings.any { it.contains("样品") })
        assertTrue(samples.phonetic.isBlank())
        val constitutes = repo.dictionary.lookup("constitutes")!!
        assertEquals("constitute",constitutes.lemma); assertTrue(constitutes.meanings.any { it.contains("构成") })
        val patch = DefaultEnrichmentProvider(repo).local(WordCard("samples","samples"))!!
        assertEquals("samples",patch.spelling); assertEquals("samples",patch.id)
        assertTrue(patch.sources.contains("sample")); assertTrue(repo.dao.words().isEmpty())
        assertTrue(repo.dictionary.lookup("sample")!!.lemma.isBlank())
        Unit
    }
    @Test fun overduePausesRestOfQueueAndSurvivesCoordinatorRestart() = runBlocking {
        repo.settings.save(AppSettings(aiEnabled=true))
        repo.addWords(listOf("apple","book","tree","dog","car"))
        val provider = object: FakeEnrichmentProvider() {
            override suspend fun sentence(card:WordCard):WordCard? = null
            override suspend fun picture(card:WordCard):WordCard? = null
            override suspend fun textAi(card:WordCard,settings:AppSettings):WordCard? {
                textCalls++; throw ApiErrors.service(403,"{\"error\":{\"code\":\"AccountOverdueError\"}}")
            }
        }
        val queue = EnrichmentQueue(repo,provider,false)
        queue.enqueue(); queue.runBatch()
        assertTrue(repo.settings.get().enrichmentPaused); assertTrue(repo.settings.get().aiAccountNotice.contains("欠费"))
        assertTrue(provider.textCalls in 1..2); assertTrue(repo.dao.words().all { it.textReady() })
        val calls = provider.textCalls
        assertFalse(EnrichmentQueue(repo,provider,false).runBatch())
        queue.enqueue(failedOnly=true); assertTrue(repo.settings.get().enrichmentPaused)
        assertEquals(calls,provider.textCalls)
        queue.continueFree(); queue.runBatch()
        assertFalse(repo.settings.get().aiEnabled); assertTrue(repo.settings.get().aiAccountNotice.isBlank())
        assertEquals(calls,provider.textCalls); assertTrue(repo.dao.words().all { it.textReady() })
        Unit
    }
    @Test fun missingOptionalChunksDoesNotFailCompleteCard() = runBlocking {
        repo.addWords(listOf("apple"))
        val provider = object: FakeEnrichmentProvider() {
            override suspend fun local(card:WordCard):WordCard? = WordCard(card.id,card.spelling,meaning="苹果")
        }
        val queue = EnrichmentQueue(repo,provider,false); queue.enqueue(); queue.runBatch()
        assertEquals("success",repo.dao.job("apple")!!.state)
        assertTrue(repo.dao.word("apple")!!.error.contains("可靠分块"))
        assertTrue(repo.dao.word("apple")!!.complete())
        Unit
    }
    @Test fun emptyFreeResultIsPartialAndCanBeExplicitlyRetried() = runBlocking {
        repo.addWords(listOf("apple"))
        val provider = object: FakeEnrichmentProvider() {
            override suspend fun sentence(card:WordCard):WordCard? = null
            override suspend fun picture(card:WordCard):WordCard? = null
        }
        val queue = EnrichmentQueue(repo,provider,false); queue.enqueue(); queue.runBatch()
        assertEquals("partial",repo.dao.job("apple")!!.state)
        assertEquals(1,queue.enqueue(failedOnly=true))
        Unit
    }
}
