package com.example

import com.example.grounding.WebDocument
import org.json.JSONArray
import org.json.JSONObject

/** Historical synthetic HTML describes links; the production reader receives API JSON. */
internal fun ddgFixture(url: String, html: String): WebDocument {
    val rows = JSONArray()
    HtmlFixtureParser.links(WebDocument("https://html.duckduckgo.com/html/", html, "text/html")).forEach {
        rows.put(JSONObject().put("FirstURL", it.url).put("Text", it.title))
    }
    return WebDocument(url, JSONObject().put("Results", rows).toString(), "application/json")
}
internal fun fixtureLinks(html: String) = HtmlFixtureParser.links(WebDocument(
    "https://html.duckduckgo.com/html/", html, "text/html")).map { it.url }
