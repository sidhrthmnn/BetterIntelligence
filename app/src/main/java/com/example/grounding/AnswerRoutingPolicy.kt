package com.example.grounding

import com.example.data.ChatMessageEntity
import java.time.LocalDate

enum class AnswerPath { LOCAL, WEB_GROUNDED, CLARIFICATION }
data class AnswerRoute(val path: AnswerPath, val question: String, val reason: String,
    val requiresLive: Boolean = false, val requiresVerification: Boolean = false,
    val clarification: String = "", val localPart: String? = null,
    val reusedSources: List<EvidenceSource> = emptyList(), val privacyBlocked: Boolean = false,
    val researchAllowed: Boolean = false, val webDisabled: Boolean = false)
data class AnswerRecovery(val localQuestion: String?, val notice: String, val status: String)

/** Chat-only intent routing. No additional model run; private history stays on device. */
object AnswerRoutingPolicy {
    private val transformation = Regex("(?i)^\\s*(?:please\\s+)?(?:write|draft|compose|imagine|brainstorm|translate|rewrite|polish|proofread|summari[sz]e|format|extract|convert)\\b|^(?:എഴുത|വിവർത്തനം|തിരുത്ത)")
    private val explicitResearch = Regex("(?i)(?:^\\s*(?:please\\s+)?|\\b(?:can|could|would) you\\s+|\\b(?:and|also)\\s+)(?:search(?: the web| online| for)?|look up|check online|research|find (?:official |public )?(?:sources|documentation))\\b|\\b(?:with|using|cite|give|provide|include) (?:cited |official |reliable )?(?:sources|citations|references)\\b|വെബിൽ|തിരയുക|അന്വേഷിക്കുക")
    private val verification = Regex("(?i)(?:^\\s*(?:please\\s+)?|\\b(?:can|could|would) you\\s+|\\b(?:and|also)\\s+)(?:verify|fact[- ]?check|cite|check (?:this|the) claim)\\b|\\b(?:with|using) (?:official )?(?:sources|citations|evidence)\\b|\\bofficial documentation\\b|സത്യമാണോ|സ്ഥിരീകരിക്കുക")
    private val fresh = Regex("(?i)\\b(?:latest|today|tonight|yesterday|currently|recent|this (?:week|month|year)|right now|news|headlines|current)\\b|ഇന്ന്|ഇപ്പോൾ|ഇപ്പോഴത്തെ|നിലവിലെ|ഏറ്റവും പുതിയ|പുതിയ വാർത്ത")
    private val technicalContext = Regex("(?i)\\b(?:current|recent|latest) (?:code|flow|messages?|conversation|example|implementation|function|snippet|draft|project|request|sentence|text)\\b")
    private val explanation = Regex("(?i)^\\s*(?:explain|define|describe|help me|how (?:does|do|can|could|to)|why (?:does|do|is|are)|what (?:is|are|does)|what['’]s the difference)\\b|വിശദീകരി|എങ്ങനെ|എന്താണ്|എന്തുകൊണ്ട്")
    private val concept = Regex("(?i)\\b(?:work|works|working|concept|definition|meaning|role|forecasting|measured|calculate|calculated|measurement|difference between|stand for)\\b")
    private val changing = Regex("(?i)\\b(?:weather|forecast|temperature|exchange rates?|stock prices?|price of|cost of|population|security patch|security update|support end|latest stable|supported (?:android |runtime )?versions?)\\b|കാലാവസ്ഥ|താപനില|വില|ജനസംഖ്യ")
    private val compatibility = Regex("(?i)\\b(?:compatible|compatibility|supports?|supported|requirements?|specifications?|benchmarks?|release date|launch date|available)\\b")
    private val product = Regex("(?i)\\b(?:android|phones?|pixel|iphone|litert(?:-lm)?|llama\\.cpp|gguf|qwen|gemma|runtimes?|gpu|npu|snapdragon|adreno)\\b")
    private val followUp = Regex("(?i)^\\s*(?:what about|how about|and |can it|does it|is it|explain it|explain that|make it|why is that)|\\b(?:he|she|his|her|there|that|it|its|previous example)\\b|അത്|അതിന്റെ|അവൻ|അവൾ")
    fun localOnly(question: String): Boolean = Regex("(?i)\\b(?:offline|without (?:web|search)|do not search|don't search)\\b|(?:^|[.;]\\s*|\\bplease\\s+)(?:(?:work|answer|respond|stay)\\s+)?locally\\b|(?:do not|don't) (?:use|search) (?:the )?(?:web|internet)|ഓഫ്‌ലൈൻ|വെബ് വേണ്ട").containsMatchIn(question)

    fun decide(question: String, allowed: Boolean = true, mode: GroundingMode = GroundingMode.AUTO,
        history: List<ChatMessageEntity> = emptyList(), today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): AnswerRoute {
        val q = question.trim()
        val instruction = q.substringBefore('\n')
        val explicit = explicitResearch.containsMatchIn(instruction) || verification.containsMatchIn(instruction) || Regex("https?://").containsMatchIn(instruction)
        val verify = verification.containsMatchIn(instruction) || Regex("https?://").containsMatchIn(instruction)
        val privateInput = GroundingPolicy.containsCredential(q) || !explicit && Regex("(?i)\\b(?:my|our)\\s+(?:(?:current|recent|latest|private)\\s+)?(?:password|secret|key|token|chat|messages|notes|project|code|email|salary|medical)\\b").containsMatchIn(q)
        val calculation = com.example.engine.LocalCalculation.evaluate(q) != null
        val transform = transformation.containsMatchIn(q) && !explicitResearch.containsMatchIn(q.substringBefore('\n').substringBefore(": ")) && !Regex("https?://").containsMatchIn(q.substringBefore('\n'))
        val resolved = if (transform || calculation || privateInput || localOnly(q)) q else ConversationalResearch.question(q, history)
        val publicQuestion = resolved.substringBefore("(Public source topic:").trim()
        val signals = publicQuestion.substringBefore('\n').replace(technicalContext, "task context")
        val personalContext = !explicit && Regex("(?i)\\b(?:my|our|I|we)\\b|എന്റെ|ഞാൻ|നമ്മുടെ").containsMatchIn(instruction) &&
            !changing.containsMatchIn(signals) && !product.containsMatchIn(signals) && !ResearchQuery.isCurrentIncumbentQuery(signals)
        val current = fresh.containsMatchIn(signals) || NewsIntent.isLiveRequest(signals)
        val general = explanation.containsMatchIn(signals) && !current && (concept.containsMatchIn(signals) ||
            Regex("(?i)^\\s*(?:explain|define|describe|how (?:does|do|to)|why)\\b|വിശദീകരി|എങ്ങനെ|എന്താണ്").containsMatchIn(signals))
        val historic = !current && (Regex("(?i)\\b(?:history|historical|who was|when did|what year did)\\b").containsMatchIn(signals) ||
            Regex("\\b(?:18|19|20)\\d{2}\\b").findAll(signals).any { it.value.toInt() < today.year - 1 })
        val live = !transform && !calculation && !historic && !general && !personalContext && (current || changing.containsMatchIn(signals) ||
            Regex("(?i)\\b(?:who won|winner of)\\b").containsMatchIn(signals) ||
            ResearchQuery.isCurrentIncumbentQuery(signals) || FactualResearch.isCasting(signals) ||
            product.containsMatchIn(signals) && (compatibility.containsMatchIn(signals) || Regex("(?i)\\b(?:costs?|prices?)\\b").containsMatchIn(signals)))
        val localPart = if (live && !verify) mixedLocalPart(q) else null
        val wantsWeb = explicit || live || ConversationalResearch.isRecheck(q) && resolved.contains("(Public source topic:") || mode == GroundingMode.ON
        val canSearch = allowed && mode != GroundingMode.OFF && !localOnly(q) && !privateInput && !personalContext && (!transform || mode == GroundingMode.ON) && !calculation
        if (history.none { it.role == "user" } && ConversationalResearch.needsSubject(q))
            return AnswerRoute(AnswerPath.CLARIFICATION, q, "Missing subject", clarification = ConversationalResearch.askSubject)
        if (canSearch && wantsWeb) {
            val clarification = when {
                ConversationalResearch.needsSubject(resolved) -> ConversationalResearch.askSubject
                ConversationalResearch.needsPlace(resolved) -> ConversationalResearch.askPlace
                ConversationalResearch.needsNewsTopic(resolved) -> ConversationalResearch.askNewsTopic
                ConversationalResearch.isWeather(resolved) && !general && ConversationalResearch.weatherCity(resolved) == null &&
                    !Regex("https?://").containsMatchIn(resolved) -> ConversationalResearch.askCity
                else -> ""
            }
            if (clarification.isNotBlank()) return AnswerRoute(AnswerPath.CLARIFICATION, resolved, "Missing public subject or place", live, verify, clarification)
            val reuse = if (!explicit && !current && followUp.containsMatchIn(q)) reusableEvidence(resolved, history, now) else emptyList()
            return AnswerRoute(AnswerPath.WEB_GROUNDED, resolved, if (explicit) "Requested external evidence" else "Changing facts or runtime compatibility", live, verify, localPart = localPart, reusedSources = reuse, researchAllowed = true)
        }
        return AnswerRoute(AnswerPath.LOCAL, q, when {
            privateInput -> "Possible credentials stay on device"
            !canSearch && wantsWeb -> "Web unavailable or explicitly disabled"
            transform -> "Writing or supplied-text task"
            calculation -> "Local calculation"
            else -> "Established knowledge, reasoning or conversation"
        }, live, verify, localPart = localPart, privacyBlocked = GroundingPolicy.containsCredential(q),
            researchAllowed = canSearch, webDisabled = !allowed || mode == GroundingMode.OFF)
    }

    /** One recovery search only when a public factual answer itself needs current data.
     * Eligibility is fixed before inference: model text cannot override privacy/offline choices. */
    fun shouldResearchAfterLocalAnswer(route: AnswerRoute, answer: String): Boolean =
        route.path == AnswerPath.LOCAL && route.researchAllowed && !route.privacyBlocked &&
            Regex("(?i)^\\s*(?:who|what|whats|when|where|which|how much|tell me about)\\b").containsMatchIn(route.question) &&
            Regex("(?is)\\b(?:do not|don't|don’t|cannot|can't|can’t)\\s+(?:currently\\s+)?(?:have|access|provide)\\b.{0,75}\\b(?:real[- ]?time|up[- ]to[- ]date|current (?:information|events|data)|internet access)\\b|തത്സമയ വിവരങ്ങൾ.{0,30}(?:ലഭ്യമല്ല|അറിയില്ല)").containsMatchIn(answer)

    private fun mixedLocalPart(question: String): String? {
        val parts = question.split(Regex("(?i),?\\s+and\\s+(?=what|which|who|when|where)|;\\s*|\\s+കൂടാതെ\\s+"), limit = 2)
        return parts.firstOrNull()?.trim(' ', ',', '?')?.takeIf {
            parts.size == 2 && explanation.containsMatchIn(it) && !fresh.containsMatchIn(it) && !changing.containsMatchIn(it) &&
                !verification.containsMatchIn(it) && !compatibility.containsMatchIn(it)
        }
    }

    /** Failure recovery never manufactures evidence or current observations. */
    fun recover(route: AnswerRoute, failure: ResearchFailure? = null): AnswerRecovery {
        val ml = Regex("[\\u0D00-\\u0D7F]").containsMatchIn(route.question)
        if (route.webDisabled && (route.requiresLive || route.requiresVerification)) return AnswerRecovery(route.localPart,
            if (ml) "വെബ് ഗവേഷണം ഓഫാണ്. ക്രമീകരണങ്ങളിൽ അത് പ്രവർത്തനക്ഷമമാക്കാം." else
                "Web research is off. Enable it in Web & grounding to check current sources.", "WEB_DISABLED")
        val liveNotice = if (ml) "തത്സമയ വിവരങ്ങൾ ഇപ്പോൾ പരിശോധിക്കാൻ കഴിഞ്ഞില്ല." else if (ConversationalResearch.isWeather(route.question))
            "I couldn't check the live weather or temperature right now." else "I couldn't check the current information right now."
        if (route.requiresVerification) return AnswerRecovery(null, if (ml) "ആവശ്യപ്പെട്ട സ്രോതസ്സുകൾ പരിശോധിച്ച് ഉറപ്പിക്കാൻ കഴിഞ്ഞില്ല." else
            "I couldn't verify this from the requested sources right now. You can try another public HTTPS source.", "WEB_UNAVAILABLE")
        if (route.requiresLive && route.localPart == null) return AnswerRecovery(null, liveNotice, "WEB_UNAVAILABLE")
        if (route.localPart != null) return AnswerRecovery(route.localPart, liveNotice, "LOCAL_WITH_WEB_GAP")
        return AnswerRecovery(route.question, if (route.privacyBlocked) "" else if (failure != null) {
            if (ml) "വെബ് പരിശോധിക്കാനായില്ല; ഈ മറുപടി ഉപകരണത്തിലെ മോഡലിൽ നിന്നുള്ളതാണ്." else "Web research was unavailable; this answer uses on-device knowledge."
        } else "", if (route.privacyBlocked) "PRIVACY_BLOCKED" else if (failure != null) "LOCAL_WEB_UNAVAILABLE" else "LOCAL")
    }

    private fun reusableEvidence(question: String, history: List<ChatMessageEntity>, now: Long): List<EvidenceSource> {
        val last = history.lastOrNull { it.role == "assistant" && it.groundingStatus in setOf("EVIDENCE_CITED", "PARTIALLY_SUPPORTED", "SOURCE_EXCERPTS") } ?: return emptyList()
        val age = if (ConversationalResearch.isWeather(question)) 60_000L else 300_000L
        return GroundingResult.sources(last.sourcesJson).filter { now - it.retrievedAt in 0..age &&
            FactualResearch.usable(it, question) && ResearchQuery(question).score(it.title + " " + it.excerpt) > 0 }
    }
}
