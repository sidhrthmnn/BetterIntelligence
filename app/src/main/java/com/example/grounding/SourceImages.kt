package com.example.grounding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import kotlin.coroutines.coroutineContext

/** Publisher-provided illustrations only. Never consumes model-authored URLs,
 * executes HTML, interprets image contents or stores images on disk. */
object SourceImages {
    fun metadata(doc: Document, pageUrl: String, policies: List<String>): Pair<String, String>? {
        if (policies.any { Regex("(?i)\\b(?:noimageindex|nosnippet|none)\\b").containsMatchIn(it) }) return null
        val candidate = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        val url = pageUrl.toHttpUrl().resolve(candidate)?.toString() ?: return null
        if (candidate.isBlank() || !GroundingPolicy.publicHttpsUrl(url) ||
            Regex("(?i)(?:logo|favicon|placeholder|tracking|pixel\\.|/ads?/)").containsMatchIn(url)) return null
        return url to doc.selectFirst("meta[property=og:image:alt]")?.attr("content").orEmpty().take(160)
    }

    suspend fun load(source: EvidenceSource, allowed: () -> Boolean): Bitmap = withTimeout(8_000) {
        check(source.kind == "WEB_PAGE" && GroundingPolicy.publicHttpsUrl(source.imageUrl) && allowed())
        val http = SearchHttp()
        // Every request, including robots redirects, checks current consent.
        val guarded = object : WebFetcher {
            override suspend fun fetch(url: String) = http.fetchPage(url) { allowed() }
        }
        val robots = ResearchRobots(guarded)
        val data = http.fetchImage(source.imageUrl) { allowed() && robots.allowed(it) && allowed() }
        withContext(Dispatchers.Default) {
            coroutineContext.ensureActive(); check(allowed())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            check(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "Image dimensions unavailable or too large" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 768 || bounds.outHeight / inSampleSize > 768) inSampleSize *= 2
            }
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options))
            if (!allowed()) { bitmap.recycle(); error("Web access is off") }
            bitmap
        }
    }
}
