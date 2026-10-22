package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v0.23.4 Auto routing: every model per provider, round-robin, failure memory, cooldowns, tools. */
class AutoRouterTest {

    private var t = 1_800_000_000_000L

    @Before fun setUp() {
        ModelMemory.resetForTests(); ProviderCatalog.clearForTests()
        ModelMemory.clock = { t }
    }

    @After fun tearDown() { ModelMemory.resetForTests(); ProviderCatalog.clearForTests() }

    private fun c(pid: String, model: String, tools: Boolean = true) = RoutePlan.Cand(pid, model, tools)
    private fun ids(p: RoutePlan.Plan) = p.ready.map { "${it.pid}|${it.model}" }

    // ---------------- catalogue filtering ----------------

    @Test fun nvidiaListKeepsChatModelsOnly() {
        val body = """{"data":[{"id":"moonshotai/kimi-k2.6"},{"id":"nvidia/nv-embedqa-mistral-7b-v2"},{"id":"meta/llama-guard-4-12b"},
            {"id":"nvidia/nemotron-4-340b-reward"},{"id":"nvidia/nemotron-3.5-content-safety"},{"id":"nvidia/llama-3.2-nemoretriever-1b-vlm-embed-v1"},
            {"id":"google/deplot"},{"id":"nvidia/nvclip"},{"id":"z-ai/glm-5.3"},{"id":"openai/gpt-oss-20b"},{"id":"bigcode/starcoder2-15b"},
            {"id":"nvidia/riva-translate-4b-instruct"},{"id":"nvidia/nemotron-parse"}]}"""
        val got = ProviderCatalog.parse("nvidia", body).map { it.id }
        assertEquals(listOf("moonshotai/kimi-k2.6", "z-ai/glm-5.3", "openai/gpt-oss-20b"), got)
        assertTrue(ProviderCatalog.parse("nvidia", body).all { it.tools })   // known tool families
    }

    @Test fun groqDropsRetiredWhisperTtsAndGuard() {
        val body = """{"data":[{"id":"llama-3.3-70b-versatile","active":true,"context_window":131072},{"id":"whisper-large-v3","active":true},
            {"id":"playai-tts","active":true},{"id":"meta-llama/llama-prompt-guard-2-86m","active":true},{"id":"gemma2-9b-it","active":false},
            {"id":"qwen/qwen3-32b","active":true}]}"""
        val got = ProviderCatalog.parse("groq", body)
        assertEquals(listOf("llama-3.3-70b-versatile", "qwen/qwen3-32b"), got.map { it.id })
        assertEquals(131072, got[0].ctx)
    }

    @Test fun openRouterKeepsFreeTextModelsAndReadsToolSupport() {
        val body = """{"data":[
            {"id":"a/strong-120b:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","temperature"],"architecture":{"output_modalities":["text"]}},
            {"id":"b/chat-only:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"]},
            {"id":"c/paid","pricing":{"prompt":"0.000001","completion":"0.000002"},"supported_parameters":["tools"]},
            {"id":"d/zero-price","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
            {"id":"e/painter:free","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["image","text"]}},
            {"id":"f/old:free","pricing":{"prompt":"0","completion":"0"},"expiration_date":"2020-01-01"},
            {"id":"nvidia/nemotron-3.5-content-safety:free","pricing":{"prompt":"0","completion":"0"}}]}"""
        val got = ProviderCatalog.parse("openrouter", body, now = 1_800_000_000_000L)
        assertEquals(listOf("a/strong-120b:free", "b/chat-only:free", "d/zero-price"), got.map { it.id })
        assertEquals(listOf(true, false, true), got.map { it.tools })
    }

    @Test fun geminiKeepsGenerateContentChatModels() {
        val body = """{"models":[
            {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"],"inputTokenLimit":1048576},
            {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-3.8-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.8-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemma-4-31b-it","supportedGenerationMethods":["generateContent"]},
            {"name":"models/imagen-4","supportedGenerationMethods":["predict"]}]}"""
        val got = ProviderCatalog.parse("gemini", body)
        assertEquals(listOf("gemini-3.8-flash", "gemma-4-31b-it"), got.map { it.id })
        assertEquals(listOf(true, false), got.map { it.tools })   // Gemma on the Gemini API: chat only
    }

    @Test fun mistralUsesCapabilitiesAndDropsDeprecated() {
        val body = """{"data":[
            {"id":"mistral-small-latest","capabilities":{"completion_chat":true,"function_calling":true}},
            {"id":"mistral-embed","capabilities":{"completion_chat":false}},
            {"id":"pixtral-12b","capabilities":{"completion_chat":true,"function_calling":false}},
            {"id":"open-mistral-7b","capabilities":{"completion_chat":true,"function_calling":true},"deprecation":"2021-03-30"}]}"""
        val got = ProviderCatalog.parse("mistral", body, now = 1_800_000_000_000L)
        assertEquals(listOf("mistral-small-latest", "pixtral-12b"), got.map { it.id })
        assertEquals(listOf(true, false), got.map { it.tools })
    }

    @Test fun pollinationsPublicList() {
        val body = """[{"name":"openai-fast","tools":true,"output_modalities":["text"]},{"name":"openai-audio","audio":true}]"""
        assertEquals(listOf("openai-fast"), ProviderCatalog.parse("pollinations", body).map { it.id })
    }

    // ---------------- ordering ----------------

    @Test fun curatedDefaultsFirstThenBySizeAndQuality() {
        val e = listOf("x/tiny-1b-instruct", "y/coder-32b", "z/glm-5.3", "w/llama-3.3-70b-instruct", "q/model-8b", "fav/pick")
            .map { ProviderCatalog.Entry(it, true) }
        val got = ProviderCatalog.order(e, listOf("fav/pick", "not/listed")).map { it.id }
        assertEquals("fav/pick", got[0])
        assertTrue(got.indexOf("z/glm-5.3") < got.indexOf("q/model-8b"))
        assertTrue(got.indexOf("w/llama-3.3-70b-instruct") < got.indexOf("y/coder-32b"))
        assertEquals("x/tiny-1b-instruct", got.last())
        assertFalse("not/listed" in got)
    }

    @Test fun geminiIsNotPenalisedAsMini() {
        assertTrue(ProviderCatalog.score("gemini-3.8-flash") > ProviderCatalog.score("gpt-4o-mini"))
    }

    @Test fun pinnedPickLeadsAndDefaultsFillInWhenNothingFetched() {
        val l = ProviderCatalog.models("groq", listOf("my/own-model"))
        assertEquals("my/own-model", l.first().id)
        assertTrue(l.any { it.id == "llama-3.3-70b-versatile" })
    }

    @Test fun fetchedListIsUsedAndCachedListSurvivesAFailedRefresh() {
        ProviderCatalog.fetcher = { """{"data":[{"id":"a/llama-3.3-70b"},{"id":"b/whisper-1"}]}""" }
        ProviderCatalog.refresh("cerebras")
        assertEquals(listOf("a/llama-3.3-70b"), ProviderCatalog.listing("cerebras")!!.models.map { it.id })
        ProviderCatalog.fetcher = { throw Exception("offline") }
        ProviderCatalog.refresh("cerebras")
        val l = ProviderCatalog.listing("cerebras")!!
        assertEquals(listOf("a/llama-3.3-70b"), l.models.map { it.id })
        assertEquals("offline", l.error)
        assertTrue(ProviderCatalog.models("cerebras").any { it.id == "a/llama-3.3-70b" })
    }

    @Test fun staleAfter24h() {
        ProviderCatalog.put("groq", listOf(ProviderCatalog.Entry("m", true)), at = 1_000L)
        assertFalse(ProviderCatalog.needsRefresh("groq", now = 1_000L + 3600_000L))
        assertTrue(ProviderCatalog.needsRefresh("groq", now = 1_000L + ProviderCatalog.TTL_MS + 1))
    }

    // ---------------- rotation ----------------

    private val pools = listOf(
        "gemini" to listOf(c("gemini", "g1"), c("gemini", "g2"), c("gemini", "g3")),
        "groq" to listOf(c("groq", "q1"), c("groq", "q2")),
        "nvidia" to listOf(c("nvidia", "n1")),
    )

    @Test fun providersTakeTurnsModelByModel() {
        val p = RoutePlan.plan(pools, needTools = false, tried = emptySet(), now = t)
        assertEquals(listOf("gemini|g1", "groq|q1", "nvidia|n1", "gemini|g2", "groq|q2", "gemini|g3"), ids(p))
    }

    @Test fun triedThisTurnIsSkippedAndResetsNextTurn() {
        val p = RoutePlan.plan(pools, false, setOf("gemini|g1", "groq|q1"), t)
        assertEquals(listOf("gemini|g2", "groq|q2", "nvidia|n1", "gemini|g3"), ids(p))
        assertEquals("gemini|g1", ids(RoutePlan.plan(pools, false, emptySet(), t)).first())
    }

    @Test fun failedModelIsNotRetriedUntilEveryOtherWasTried() {
        ModelMemory.failed("gemini", "g1", 2 * 60_000L, "500")
        ModelMemory.limited("groq", "q1", 60_000L, "rate limit")
        val p = RoutePlan.plan(pools, false, emptySet(), t)
        assertEquals(listOf("gemini|g2", "groq|q2", "nvidia|n1", "gemini|g3"), ids(p))
        assertEquals(listOf("groq|q1", "gemini|g1"), p.cooling.map { "${it.first.pid}|${it.first.model}" })
        // Cooldown over: the rate-limited model is back in its place; the hard failure waits at the back.
        t += 3 * 60_000L
        val p2 = RoutePlan.plan(pools, false, emptySet(), t)
        assertEquals(listOf("gemini|g2", "groq|q1", "nvidia|n1", "gemini|g3", "groq|q2", "gemini|g1"), ids(p2))
        // It works again: no longer demoted.
        ModelMemory.ok("gemini", "g1")
        assertEquals("gemini|g1", ids(RoutePlan.plan(pools, false, emptySet(), t)).first())
    }

    @Test fun cooldownsPersistAcrossTurnsAndExpire() {
        ModelMemory.limited("nvidia", "n1", 30_000L, "rate limit")
        assertTrue(ModelMemory.cooling("nvidia", "n1"))
        assertFalse("nvidia|n1" in ids(RoutePlan.plan(pools, false, emptySet(), t)))
        t += 31_000L
        assertFalse(ModelMemory.cooling("nvidia", "n1"))
        assertTrue("nvidia|n1" in ids(RoutePlan.plan(pools, false, emptySet(), t)))
    }

    @Test fun providerWideMarkCoolsEveryModel() {
        ModelMemory.providerLimited("gemini", 10 * 60_000L, "key rejected")
        val p = RoutePlan.plan(pools, false, emptySet(), t)
        assertTrue(ids(p).none { it.startsWith("gemini|") })
        assertEquals(3, p.cooling.count { it.first.pid == "gemini" })
        assertEquals(0 to 3, ModelMemory.counts("gemini", listOf("g1", "g2", "g3")))
    }

    @Test fun toolTurnsUseToolCapableModelsOnly() {
        val ps = listOf("openrouter" to listOf(c("openrouter", "chatty:free", tools = false), c("openrouter", "agent:free")),
            "groq" to listOf(c("groq", "q1")))
        assertEquals(listOf("openrouter|agent:free", "groq|q1"), ids(RoutePlan.plan(ps, true, emptySet(), t)))
        assertEquals(3, RoutePlan.plan(ps, false, emptySet(), t).ready.size)
        // A model that refused our tools is kept out of tool turns only.
        ModelMemory.cantUseTools("groq", "q1")
        assertEquals(listOf("openrouter|agent:free"), ids(RoutePlan.plan(ps, true, emptySet(), t)))
        assertTrue("groq|q1" in ids(RoutePlan.plan(ps, false, emptySet(), t)))
    }

    @Test fun lastWorkingModelLeadsItsProvider() {
        ModelMemory.ok("groq", "q2")
        assertEquals(listOf("gemini|g1", "groq|q2", "nvidia|n1", "gemini|g2", "groq|q1", "gemini|g3"), ids(RoutePlan.plan(pools, false, emptySet(), t)))
    }

    @Test fun quickTurnsCanStartWithGroq() {
        assertEquals("groq|q1", ids(RoutePlan.plan(pools, false, emptySet(), t, firstPid = "groq")).first())
    }

    // ---------------- classification and messages ----------------

    @Test fun classifiesProviderErrors() {
        assertEquals(ModelMemory.Kind.GONE, ModelMemory.classify("groq", 400, "The model `x` has been decommissioned").kind)
        assertEquals(ModelMemory.DECOMMISSIONED_MS, ModelMemory.classify("nvidia", 404, "not found").coolMs)
        assertEquals(ModelMemory.Kind.RATE_LIMIT, ModelMemory.classify("groq", 429, "Rate limit reached", 12_000L).kind)
        assertEquals(12_000L, ModelMemory.classify("groq", 429, "Rate limit reached", 12_000L).coolMs)
        assertEquals(ModelMemory.Kind.PROVIDER_LIMIT, ModelMemory.classify("openrouter", 429, "Rate limit exceeded: free-models-per-day").kind)
        assertEquals(ModelMemory.Kind.KEY, ModelMemory.classify("mistral", 401, "Unauthorized").kind)
        assertEquals(ModelMemory.Kind.NO_TOOLS, ModelMemory.classify("cerebras", 400, "tools are not supported for this model").kind)
        assertEquals(ModelMemory.Kind.SERVER, ModelMemory.classify("nvidia", 503, "overloaded").kind)
        assertEquals(ModelMemory.Kind.TIMEOUT, ModelMemory.classify("nvidia", 0, "didn't start answering in 40s.").kind)
        assertEquals(ModelMemory.Kind.OFFLINE, ModelMemory.classify("groq", 0, "Groq: couldn't reach the service (Unable to resolve host)").kind)
        assertEquals(ModelMemory.Kind.TOO_LARGE, ModelMemory.classify("groq", 413, "Request too large").kind)
    }

    @Test fun friendlyMessageSaysWhenTheNextModelFreesUp() {
        val m = FreeRouter.friendly(0, false, "Groq · llama-3.3-70b-versatile" to 40_000L, false)
        assertTrue(m.contains("40s")); assertTrue(m.contains("Groq"))
        assertTrue(m.length < 220)   // PlainError keeps short messages as they are
        assertTrue(FreeRouter.friendly(10, true, null, true).contains("try again"))
    }
}
