package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Small robots policy cache, scoped to all public HTML reads including discovery.
 * A failed policy fetch is not permission to crawl.
 */
class ResearchRobots(private val fetcher: WebFetcher) {
    private val cache = LinkedHashMap<String, Pair<Long, String?>>()
    private val locks = LinkedHashMap<String, Mutex>()
    suspend fun allowed(url: String): Boolean {
        val parsed = url.toHttpUrl()
        val origin = "https://${parsed.host}"
        val lock = synchronized(locks) {
            locks.getOrPut(origin) { if (locks.size >= 24) locks.remove(locks.keys.first()); Mutex() }
        }
        val policy = lock.withLock {
            val entry = synchronized(cache) { cache[origin]?.takeIf { System.currentTimeMillis() - it.first < if (it.second == null) 60_000 else 3_600_000 } }
            if (entry != null) return@withLock entry.second
            val text = withTimeoutOrNull(3_000) { try {
                val page = fetcher.fetch("$origin/robots.txt")
                // A CAPTCHA/HTML error page is not an empty robots policy.
                if (page.text.trimStart().startsWith('<')) null else page.text.take(64_000)
            }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if ("HTTP 404" in e.message.orEmpty() || "HTTP 410" in e.message.orEmpty()) "" else null } }
            synchronized(cache) { if (cache.size >= 24) cache.remove(cache.keys.first()); cache[origin] = System.currentTimeMillis() to text }
            text
        }
        return policy != null && permits(policy, parsed.encodedPath + parsed.encodedQuery?.let { "?$it" }.orEmpty())
    }
    companion object {
        fun permits(text: String, path: String): Boolean {
            data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Pair<String, Boolean>> = mutableListOf())
            val groups = mutableListOf<Group>(); var group = Group()
            for (line in text.lineSequence()) {
                val parts = line.substringBefore('#').split(':', limit = 2)
                if (parts.size != 2) continue
                val key = parts[0].trim().lowercase(); val value = parts[1].trim()
                if (key == "user-agent") {
                    if (group.rules.isNotEmpty()) { groups += group; group = Group() }
                    group.agents += value.lowercase()
                } else if (key in setOf("allow", "disallow") && value.isNotEmpty()) group.rules += value to (key == "allow")
            }
            groups += group
            val specific = groups.filter { it.agents.any { agent -> agent != "*" && "betterintelligence".contains(agent) } }
            val applicable = specific.ifEmpty { groups.filter { "*" in it.agents } }
            return applicable.flatMap { it.rules }.filter { (pattern, _) ->
                val regex = pattern.split('*').joinToString(".*") { Regex.escape(it.removeSuffix("$")) } + if (pattern.endsWith('$')) "$" else ".*"
                Regex("^$regex").matches(path)
            }.sortedWith(compareByDescending<Pair<String, Boolean>> { it.first.length }.thenByDescending { it.second }).firstOrNull()?.second ?: true
        }
    }
}
