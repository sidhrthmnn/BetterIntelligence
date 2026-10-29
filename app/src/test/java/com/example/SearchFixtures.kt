package com.example

import com.example.grounding.HtmlDiscovery
import com.example.grounding.WebDocument
import org.json.JSONArray
import org.json.JSONObject

/** Keep historical page-ranking fixtures while serving Mwmbl's actual JSON schema. */
internal fun mwmblFixture(url: String, html: String): WebDocument {
    val rows = JSONArray()
    HtmlDiscovery.links(WebDocument("https://html.duckduckgo.com/html/", html, "text/html")).forEach {
        rows.put(JSONObject().put("url", it.url).put("title", it.title).put("content", it.routingHint))
    }
    return WebDocument(url, JSONObject().put("results", rows).toString(), "application/json")
}
