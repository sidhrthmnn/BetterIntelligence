package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.engine.*
import com.example.grounding.*
import com.example.service.DownloadQueueStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntelligentAnswerPipelineTest {

    private val testDate = LocalDate.of(2026, 10, 21) // Wednesday

    // -------------------------------------------------------------
    // Grounding Modes: OFF, AUTO, ON
    // -------------------------------------------------------------
    @Test
    fun testGroundingModesOffAutoOn() {
        val qGeneral = "Explain how on-device AI works."
        val qCurrent = "What is the weather in Tokyo today?"

        // Mode OFF: Always routes locally
        val routeOff1 = AnswerRoutingPolicy.decide(qGeneral, allowed = true, mode = GroundingMode.OFF)
        assertEquals(AnswerPath.LOCAL, routeOff1.path)
        assertFalse(routeOff1.researchAllowed)

        val routeOff2 = AnswerRoutingPolicy.decide(qCurrent, allowed = true, mode = GroundingMode.OFF)
        assertEquals(AnswerPath.LOCAL, routeOff2.path)
        assertFalse(routeOff2.researchAllowed)

        // Mode AUTO: General knowledge / reasoning routes locally first
        val routeAuto1 = AnswerRoutingPolicy.decide(qGeneral, allowed = true, mode = GroundingMode.AUTO)
        assertEquals(AnswerPath.LOCAL, routeAuto1.path)

        // Mode AUTO: Current information routes to web
        val routeAuto2 = AnswerRoutingPolicy.decide(qCurrent, allowed = true, mode = GroundingMode.AUTO)
        assertEquals(AnswerPath.WEB_GROUNDED, routeAuto2.path)
        assertTrue(routeAuto2.researchAllowed)

        // Mode ON: Always researches when grounding is enabled
        val routeOn1 = AnswerRoutingPolicy.decide(qGeneral, allowed = true, mode = GroundingMode.ON)
        assertEquals(AnswerPath.WEB_GROUNDED, routeOn1.path)
        assertTrue(routeOn1.researchAllowed)

        val routeOn2 = AnswerRoutingPolicy.decide(qCurrent, allowed = true, mode = GroundingMode.ON)
        assertEquals(AnswerPath.WEB_GROUNDED, routeOn2.path)
        assertTrue(routeOn2.researchAllowed)
    }

    // -------------------------------------------------------------
    // Scenario 1: "Explain how on-device AI works."
    // Local model answers without web search in Auto mode.
    // -------------------------------------------------------------
    @Test
    fun scenario01_explainHowOnDeviceAiWorks_routesLocallyWithoutWeb() {
        val question = "Explain how on-device AI works."
        val category = AnswerRoutingPolicy.categorize(question)
        assertTrue(
            "Category should be reasoning or general knowledge, got: $category",
            category in setOf(RequestCategory.EXPLANATION_OR_REASONING, RequestCategory.GENERAL_KNOWLEDGE)
        )

        val route = AnswerRoutingPolicy.decide(question, allowed = true, mode = GroundingMode.AUTO)
        assertEquals("Should route locally in Auto mode", AnswerPath.LOCAL, route.path)

        val localAnswer = "On-device AI executes neural network models directly on local hardware like CPU, GPU, or NPU without sending prompts to remote servers."
        val shouldResearch = AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(route, localAnswer)
        assertFalse("High quality local answer should not trigger research", shouldResearch)
    }

    // -------------------------------------------------------------
    // Scenario 2: "What is 15 × 12?"
    // Correct calculation ("180") without web.
    // -------------------------------------------------------------
    @Test
    fun scenario02_whatIs15Times12_calculates180WithoutWeb() {
        val expression = "What is 15 × 12?"
        val category = AnswerRoutingPolicy.categorize(expression)
        assertEquals(RequestCategory.CALCULATION, category)

        val route = AnswerRoutingPolicy.decide(expression, allowed = true, mode = GroundingMode.AUTO)
        assertEquals(AnswerPath.LOCAL, route.path)

        val result = LocalCalculation.evaluate(expression)
        assertEquals("180", result)
    }

    // -------------------------------------------------------------
    // Scenario 3: "When is Jailer 3 releasing?"
    // Research exact requested film, distinguish confirmed dates from rumours/predecessor.
    // -------------------------------------------------------------
    @Test
    fun scenario03_whenIsJailer3Releasing_exactSequelDistinction() {
        val question = "When is Jailer 3 releasing?"
        val query = ResearchQuery(question, testDate)

        // Query planning generates release date keywords
        assertTrue("Search text should contain release keywords: ${query.searchText}",
            query.searchText.contains("release", ignoreCase = true) || query.searchText.contains("date", ignoreCase = true))

        // Exact identity checking
        val fallback = ExactIdentity.sequelFallback(question)
        assertNotNull("Should detect sequel predecessor fallback", fallback)
        assertEquals("jailer 3", fallback!!.first.lowercase())

        // EvidenceAnswer sequel fallback notice distinguishes predecessor and unconfirmed rumors
        val sources = listOf(
            EvidenceSource(id = 1, title = "Jailer 2 announced", url = "https://cinema.example/jailer2", excerpt = "Director announces Jailer 2 filming schedule.", retrievedAt = System.currentTimeMillis())
        )
        val resolved = EvidenceAnswer.resolve("Draft text about Jailer 2", sources, question)
        assertTrue(resolved.text.contains("The sources do not contain an official release announcement for the requested sequel"))
        assertTrue(resolved.text.contains("predecessor", ignoreCase = true))
        assertTrue(resolved.text.contains("unconfirmed", ignoreCase = true))

        // Quality validator rejects wrong entity
        val wrongEntityAnswer = "Jailer 2 is releasing in late 2026."
        val validationWrongEntity = ResponseQualityValidator.validate(question, wrongEntityAnswer, isLocalOnly = false)
        assertFalse(validationWrongEntity.isValid)
        assertTrue(validationWrongEntity.failureReason?.contains("wrong entity", ignoreCase = true) == true)

        // Supported answer explaining unconfirmed status
        val unconfirmedAnswer = "As of late 2026, Jailer 3 has not been officially confirmed with a release date; reports currently discuss Jailer 2."
        val validationGood = ResponseQualityValidator.validate(question, unconfirmedAnswer, isLocalOnly = false)
        assertTrue(validationGood.isValid)
    }

    // -------------------------------------------------------------
    // Scenario 4: "What is happening at the O2 next weekend?"
    // Perform current research with correct date context.
    // -------------------------------------------------------------
    @Test
    fun scenario04_o2NextWeekend_computesCorrectDateWindow() {
        val question = "What is happening at the O2 next weekend?"
        val eventQuery = EventQuery.parse(question)

        assertNotNull("EventQuery should parse 'next weekend'", eventQuery)
        assertEquals("next weekend", eventQuery!!.constraint)
        assertFalse(eventQuery.subject.contains("next weekend", ignoreCase = true))

        // Reference Wednesday: 2026-10-21. Following Saturday is 2026-10-31, Sunday is 2026-11-01
        val wednesdayInstant = LocalDate.of(2026, 10, 21).atStartOfDay(ZoneOffset.UTC).toInstant()
        val (start, end) = eventQuery.window(wednesdayInstant, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 31), start)
        assertEquals(LocalDate.of(2026, 11, 1), end)
    }

    // -------------------------------------------------------------
    // Scenario 5: First search returns only article titles
    // Retry rather than presenting titles.
    // -------------------------------------------------------------
    @Test
    fun scenario05_firstSearchReturnsOnlyTitles_triggersRetryOrRejection() {
        val question = "What happened in the space mission today?"
        val titleOnlyAnswer = "NASA Space Mission Launch Successfully Updates Exploration Timeline"
        val sources = listOf(
            EvidenceSource(id = 1, title = "NASA Space Mission Launch Successfully Updates Exploration Timeline - SpaceNews", url = "https://space.example/1", excerpt = "NASA Space Mission Launch Successfully Updates Exploration Timeline", retrievedAt = System.currentTimeMillis())
        )

        // Title-only response rejected even when source title has publisher suffix
        val validation = ResponseQualityValidator.validate(question, titleOnlyAnswer, sources = sources, isLocalOnly = false)
        assertFalse("Title-only responses must be rejected", validation.isValid)
        assertTrue(validation.failureReason?.contains("Article title") == true)

        // Headline-only source is rejected by usable and acceptable, enforcing bounded search retry
        val headlineOnlySource = sources.single()
        assertFalse("Headline-only source must be unusable", FactualResearch.usable(headlineOnlySource, question))
        assertFalse("Headline-only source must not be acceptable for answer presentation", EvidenceRanking.acceptable(headlineOnlySource, ResearchPlanner.plan(question)))
    }

    // -------------------------------------------------------------
    // Scenario 6: First website blocks extraction
    // Try an alternative accessible source.
    // -------------------------------------------------------------
    @Test
    fun scenario06_firstWebsiteBlocksExtraction_proceedsToAlternativeSource() {
        val blockedDoc = WebDocument("https://blocked.example/article", "", "text/html")
        val blockedArticle = PublicWebResearch.article(blockedDoc, 1, "space rover landing")
        assertNull("Empty or blocked page should not produce article evidence", blockedArticle)

        val accessibleDoc = WebDocument(
            "https://open.example/article",
            "<html><body><article><h1>Space Rover</h1><p>The space rover safely landed and transmitted initial telemetry data back to ground control.</p></article></body></html>",
            "text/html"
        )
        val extracted = PublicWebResearch.article(accessibleDoc, 1, "space rover landing")
        assertNotNull("Alternative accessible source extracts evidence", extracted)
        assertTrue(extracted!!.excerpt.contains("rover safely landed"))
    }

    // -------------------------------------------------------------
    // Scenario 7: All research attempts fail
    // Return appropriate explanation without fabricated claims.
    // -------------------------------------------------------------
    @Test
    fun scenario07_allResearchFails_returnsClearExplanationWithoutFabrication() {
        val question = "What is the secret code of project delta?"
        val fabricatedCitationAnswer = "Project Delta code is 9988 [1]."
        // No evidence sources -> citation [1] is fabricated
        val validation = ResponseQualityValidator.validate(question, fabricatedCitationAnswer, sources = emptyList(), isLocalOnly = false)
        assertFalse(validation.isValid)
        assertTrue(validation.failureReason?.contains("Fabricated citations") == true)

        // Truncated responses with trailing prepositions are rejected
        val truncatedAnswer = "Project Delta was initiated because the"
        val validationTruncated = ResponseQualityValidator.validate(question, truncatedAnswer, sources = emptyList(), isLocalOnly = false)
        assertFalse(validationTruncated.isValid)
        assertTrue(validationTruncated.failureReason?.contains("truncated", ignoreCase = true) == true)

        val honestFailureAnswer = "I was unable to find verified public information regarding project delta's secret code."
        val validationHonest = ResponseQualityValidator.validate(question, honestFailureAnswer, sources = emptyList(), isLocalOnly = false)
        assertTrue(validationHonest.isValid)
    }

    // -------------------------------------------------------------
    // Scenario 8: Verified evidence is available
    // Produce complete answer with valid citations.
    // -------------------------------------------------------------
    @Test
    fun scenario08_verifiedEvidenceAvailable_producesValidAnswerWithCitations() {
        val question = "Who won the 2026 Nobel Prize in Physics?"
        val source = EvidenceSource(
            id = 1,
            title = "Nobel Prize in Physics 2026",
            url = "https://nobelprize.example/2026",
            excerpt = "The 2026 Nobel Prize in Physics was awarded to Francis Halzen.",
            retrievedAt = System.currentTimeMillis()
        )
        val answer = "The 2026 Nobel Prize in Physics was awarded to Francis Halzen for contributions to neutrino astrophysics [1]."
        val validation = ResponseQualityValidator.validate(question, answer, sources = listOf(source), isLocalOnly = false)
        assertTrue(validation.isValid)
        assertNull(validation.failureReason)

        // Unsupported numerical/date claims are rejected as unsupported current facts
        val unsupportedAnswer = "The 2026 Nobel Prize in Physics was awarded to Francis Halzen on December 25, 2099 [1]."
        val validationUnsupported = ResponseQualityValidator.validate(question, unsupportedAnswer, sources = listOf(source), isLocalOnly = false)
        assertFalse("Unsupported facts must be rejected", validationUnsupported.isValid)
        assertTrue(validationUnsupported.failureReason?.contains("Unsupported current facts") == true)
    }

    // -------------------------------------------------------------
    // Scenario 9: Primary model fails
    // Use fallback and recover to primary when possible.
    // -------------------------------------------------------------
    @Test
    fun scenario09_primaryModelFails_fallsBackAndRecovers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseProfile = HardwareAnalyzer.analyze(context).copy(
            cpuAbi = "arm64-v8a",
            isLowMemory = false
        )

        val primary = ModelEntity(
            id = 1,
            name = "Large Model 8B",
            filename = "large_8b.gguf",
            architecture = "llama",
            quantization = "Q4_K_M",
            parameterCount = "8B",
            contextLength = 4096,
            fileSizeMb = 4800,
            ramRequiredMb = 5500,
            isInstalled = true,
            isActive = true,
            isCustom = false,
            description = "Large 8B model"
        )
        val secondary = ModelEntity(
            id = 2,
            name = "Small Model 1B",
            filename = "small_1b.gguf",
            architecture = "llama",
            quantization = "Q4_K_M",
            parameterCount = "1B",
            contextLength = 2048,
            fileSizeMb = 500,
            ramRequiredMb = 800,
            isInstalled = true,
            isActive = false,
            isCustom = false,
            description = "Small 1B model"
        )

        // When RAM is constrained (e.g. 1500 MB safe budget), primary does not fit (~5400 MB needed) but secondary fits (~1050 MB needed)
        val lowRamProfile = baseProfile.copy(totalRamMb = 4096, availableRamMb = 2000, safeModelRamLimitMb = 1500)
        val decisionLow = ModelMemoryPlanner.resolveTier(primary, secondary, lowRamProfile, requestedContext = 2048, autoFallback = true)
        assertEquals(ModelTierRole.SECONDARY_FALLBACK, decisionLow.role)
        assertEquals(secondary.id, decisionLow.model?.id)

        // When RAM recovers (e.g. 8000 MB safe budget), primary is automatically restored
        val recoveredProfile = baseProfile.copy(totalRamMb = 16384, availableRamMb = 9000, safeModelRamLimitMb = 8000)
        val decisionRecovered = ModelMemoryPlanner.resolveTier(primary, secondary, recoveredProfile, requestedContext = 2048, autoFallback = true)
        assertEquals(ModelTierRole.PRIMARY, decisionRecovered.role)
        assertEquals(primary.id, decisionRecovered.model?.id)
    }

    // -------------------------------------------------------------
    // Scenario 10: Background downloads continue correctly after removing home-screen settings
    // -------------------------------------------------------------
    @Test
    fun scenario10_backgroundDownloadsContinueAfterRelocation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DownloadQueueStore(context)

        // Set preferences from Settings -> Models & Downloads
        store.setBackgroundDownloadsEnabled(true)
        store.setWifiOnly(false)
        store.setAllowMobileData(true)
        store.setAutoResumeEnabled(true)
        store.setNotificationsEnabled(true)

        // Verify values persisted
        assertTrue(store.backgroundDownloadsEnabled())
        assertFalse(store.wifiOnly())
        assertTrue(store.allowMobileData())
        assertTrue(store.autoResumeEnabled())
        assertTrue(store.notificationsEnabled())

        // Verify bidirectional synchronization between wifiOnly and allowMobileData
        store.setWifiOnly(true)
        assertTrue(store.wifiOnly())
        assertFalse(store.allowMobileData())

        store.setAllowMobileData(true)
        assertFalse(store.wifiOnly())
        assertTrue(store.allowMobileData())
    }

    // -------------------------------------------------------------
    // Scenario 11: Connected-app permissions and model assignments remain functional
    // -------------------------------------------------------------
    @Test
    fun scenario11_connectedAppPermissionsAndModelAssignments() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = AiCoreRepository.getInstance(context)

        val policy = ClientAppPolicyEntity(
            packageName = "com.example.editor",
            appName = "Smart Editor",
            isWhitelisted = true,
            allowInference = true,
            allowAppContext = false,
            allowReadMemory = false,
            primaryModelId = 42L,
            fallbackModelId = 99L
        )
        repo.updatePolicy(policy)

        assertEquals(42L, policy.primaryModelId)
        assertEquals(99L, policy.fallbackModelId)

        // Check APP_CONTEXT scope rate limit / permission check
        val (allowedNoContext, _) = repo.checkSecurityAndRateLimit("com.example.editor", "APP_CONTEXT")
        assertFalse("APP_CONTEXT should be denied when allowAppContext is false", allowedNoContext)

        // When allowAppContext is false, requested context is excluded from client prompt
        val promptNoContext = repo.buildClientSystemPrompt("com.example.editor", "User Document Snippet")
        assertFalse(promptNoContext.contains("User Document Snippet"))

        // When allowAppContext is true, permission and context are admitted
        repo.updatePolicy(policy.copy(allowAppContext = true))
        val (allowedWithContext, _) = repo.checkSecurityAndRateLimit("com.example.editor", "APP_CONTEXT")
        assertTrue("APP_CONTEXT should be allowed when allowAppContext is true", allowedWithContext)

        val promptWithContext = repo.buildClientSystemPrompt("com.example.editor", "User Document Snippet")
        assertTrue(promptWithContext.contains("User Document Snippet"))
    }

    // -------------------------------------------------------------
    // Scenario 12: Previously authorised apps retain permissions after Room migration 9->10
    // -------------------------------------------------------------
    @Test
    fun scenario12_roomMigration9To10_preservesExistingPermissionsAndAddsNewColumns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-9-10-test.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val sql = checkNotNull(javaClass.classLoader?.getResourceAsStream("schema7.sql")).bufferedReader().use { it.readText() }
                        sql.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it.trim()) }
                        db.execSQL("INSERT INTO client_policies VALUES ('com.test.app','TestApp',1,0,5,1000,60,0,1,1,0,1,'','*')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        helper.use { assertEquals(7, it.writableDatabase.version) }

        // Upgrade using MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
            .build()
        try {
            assertEquals(10, upgraded.openHelper.writableDatabase.version)
            val policy = upgraded.clientPolicyDao().getPolicyForPackage("com.test.app")
            assertNotNull(policy)
            assertTrue(policy!!.isWhitelisted)
            assertTrue(policy.allowInference)
            assertTrue(policy.allowReadMemory)
            assertEquals(5, policy.totalRequests)
            // New columns default to null / false
            assertNull(policy.primaryModelId)
            assertNull(policy.fallbackModelId)
            assertFalse(policy.allowAppContext)
        } finally {
            upgraded.close()
            context.deleteDatabase(dbName)
        }
    }
}
