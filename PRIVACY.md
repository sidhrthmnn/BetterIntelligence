# Better Intelligence privacy policy

Updated 30 October 2026 for version 25 / 2.4.0-beta5.

Developer: HaveAnIdea. Contact: siddharthpazhoor@gmail.com. Android package: com.aistudio.betterintelligence.mobile.

Better Intelligence runs downloaded AI models on your Android device. Chat, proofreading and connected-app inference have no automatic cloud fallback. Review AI-generated text before relying on it.

## Local information and your controls

Conversations, drafts, saved memories, model files and connected-app approvals are stored in the app's private Android storage. You can delete conversations and temporary search data in Settings → Privacy & data, remove models in Settings → Models, and delete memories in Settings → Memory. Deleting a model does not delete conversations. Android backup can include settings and conversations according to your system backup preferences. Model weights and provider credentials are excluded from backup and device transfer.

Memory is off by default. Enabling it lets chat save explicit reminders and brief profile statements and use active saved memories when replying. You can pause or delete individual memories. Connected apps have separate, explicit permissions for inference and reading or writing shared memories. Approving inference alone does not share personal memories. Revoke access in Settings → Connected apps.

## Internet use

Each conversation also keeps an extractive local context summary, separate from optional memory shared across chats. It retains older user statements, early project subjects and newer decisions. If a later user message refers to an assistant proposal, a labelled, unverified quotation of that proposal may also be retained. Notes remain attributed and bounded; this does not guarantee perfect recall. Editing or regenerating older messages invalidates the summary. It survives app restarts/model changes and is removed when the conversation is deleted.

For contextual web follow-ups, up to two short titles from already retrieved public sources in the same conversation can be added to the query. Private stored messages and global memories are not copied to the search provider. Weather uses the same public-page research for a city you type; no weather API, geocoding service, GPS permission or device location is used.

An internet connection is used when you discover or download models from Hugging Face and its download infrastructure. These services receive the network information needed to handle your request, including your IP address and the requested model. Model terms are linked before use where required.

Web access is optional and **requires your permission**. Public factual lookups, current facts, casting, service status, movie reviews, prices, release dates and explicit website requests trigger research. A weather clarification can combine your original weather question with the city you supply in that conversation; confirming a lookup continues that task. It does not use a city invented by an assistant, device location or unrelated earlier messages. Other eligible questions can make one research attempt if the local model admits it cannot answer. Writing, personal context, calculations and explicit offline instructions remain local. Model confidence is not a reliable truth detector: a confident but wrong local answer may still be missed.

Discovery uses only Exa's documented key-free MCP endpoint at `https://mcp.exa.ai/mcp`, calling `web_search_exa` without a key, account, authentication or payment. At most three distinct focused public questions/queries (up to 400 characters each) are sent per research request, requesting eight links. Exa receives those topics and ordinary connection information including the IP address. Its free service has usage limits. BI does not invoke Exa Agent, a cloud answer model, or a paid fallback. Search titles identify links; highlights, summaries and search-reported dates do not become publisher evidence. BI reads publisher bodies itself, subject to public-network, redirect and publisher-policy checks. Sign-in, CAPTCHA and paywalls are not bypassed. Exa-generated library/weather pages are excluded. Visited publishers receive their requested URL and connection information. Their own privacy/retention policies apply. See [Exa MCP access modes](https://exa.ai/docs/get-started/exa-mcp) and [Exa privacy policy](https://exa.ai/privacy).

No alternative engine, DuckDuckGo Instant Answer, Mwmbl, Bing RSS, search-result HTML scraping, weather API, persistent web cookie or developer-operated search proxy is used. Direct public HTTPS reading does not contact discovery. Full stored history, memories, drafts and connected-app text are not sent. A follow-up may reuse only a bounded public topic already researched in that same chat, stored with its source cards; unsent private chat text is never copied into search. Inference, page extraction, ranking and answer generation remain on-device. The per-request HTTP budget is 24, and the reader finishes before the outer deadline to preserve already-read sources when later reads time out.

Automatic research applies to new and existing chats. The Exa disclosure introduced in version 24 requires enabling Web & grounding once (consent 6). Version 25 keeps that destination and permission; earlier permission from before Exa does not authorize it. Source cards from older releases keep their historical provenance; displaying them does not contact those services. Clicking a legacy DuckDuckGo attribution opens the result page in your browser and does not run an in-app search.

Do not include passwords, API keys, confidential material or other sensitive information in a web-enabled question. A local credential-pattern check blocks some obvious secrets, but cannot identify every kind of private information. Retrieved pages are untrusted evidence. The app labels retrieval failures and supplies citations only for pages it actually retrieved; citations do not prove that a claim is true or current. When relevant pages do not settle the whole question, BI can display brief attributed passages as an explicitly incomplete source report. Reported candidates, forecasts and conflicting values are not thereby confirmed facts. Some pages cannot be read without scripts, sign-in or a supported text format.

Temporary evidence and query keys are held only in app memory, bounded to eight requests and at most 15 minutes (one minute for weather observations and five minutes for changing facts), and can be cleared in Settings. Source excerpts and links attached to an answer are stored with that conversation until you delete it. Questions, source text and search URLs are excluded from application logs. No research data is automatically uploaded to the developer.

## Connected apps, including TypeRight

Connected-app requests always stay on-device in Better Intelligence and never invoke web grounding. Each app must be explicitly approved and is subject to your permission and rate-limit choices. Access logs record operational metadata; polish input and output are excluded. App approval does not change the other app's own privacy settings. Review the other app's policy for any separate online features it offers.

## Diagnostics and support

Hardware monitoring shows local performance, memory and thermal information. Better Intelligence does not automatically upload chats, keyboard text, local model outputs or hardware benchmark reports to a developer server. If you choose to share diagnostic information, review it first. Questions and deletion/support requests can be sent to siddharthpazhoor@gmail.com or raised through the [project's GitHub issues](https://github.com/sidhrthmnn/BetterIntelligence/issues); do not post secrets or personal text in public issues.
