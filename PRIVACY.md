# Better Intelligence privacy policy

Updated 29 October 2026 for version 23 / 2.4.0-beta3.

Developer: HaveAnIdea. Contact: siddharthpazhoor@gmail.com. Android package: com.aistudio.betterintelligence.mobile.

Better Intelligence runs downloaded AI models on your Android device. Chat, proofreading and connected-app inference have no automatic cloud fallback. Review AI-generated text before relying on it.

## Local information and your controls

Conversations, drafts, saved memories, model files and connected-app approvals are stored in the app's private Android storage. You can delete conversations and temporary search data in Settings → Privacy & data, remove models in Settings → Models, and delete memories in Settings → Memory. Deleting a model does not delete conversations. Android backup can include settings and conversations according to your system backup preferences. Model weights and provider credentials are excluded from backup and device transfer.

Memory is off by default. Enabling it lets chat save explicit reminders and brief profile statements and use active saved memories when replying. You can pause or delete individual memories. Connected apps have separate, explicit permissions for inference and reading or writing shared memories. Approving inference alone does not share personal memories. Revoke access in Settings → Connected apps.

## Internet use

Each conversation also keeps an extractive local context summary, separate from optional memory shared across chats. It retains older user statements, early project subjects and newer decisions. If a later user message refers to an assistant proposal, a labelled, unverified quotation of that proposal may also be retained. Notes remain attributed and bounded; this does not guarantee perfect recall. Editing or regenerating older messages invalidates the summary. It survives app restarts/model changes and is removed when the conversation is deleted.

For contextual web follow-ups, up to two short titles from already retrieved public sources in the same conversation can be added to the query. Private stored messages and global memories are not copied to the search provider. Weather uses the same public-page research for a city you type; no weather API, geocoding service, GPS permission or device location is used.

An internet connection is used when you discover or download models from Hugging Face and its download infrastructure. These services receive the network information needed to handle your request, including your IP address and the requested model. Model terms are linked before use where required.

Web access is optional and **requires your permission**. Automatic research checks current facts, compatibility questions and explicit source requests. Writing, personal context, calculations, offline instructions and established explanations stay local by default. Research uses only DuckDuckGo's official Instant Answer API at api.duckduckgo.com, without a key, account, cookies or payment. A focused public topic (up to 400 characters) and the static application attribution parameter `t=betterintelligence` are sent. A shorter public subject can be tried if the first topic is empty; duplicate topics are not repeated, with at most three distinct API calls per research request. No Exa, Mwmbl, HTML search scraping, alternative search engine or paid fallback is used. BI tries actual publisher bodies first. For stable topics, a bounded AbstractText or Definition summary can be used if the publisher cannot be checked, with explicit API provenance and an unknown source date. These summaries do not establish live or explicitly verified facts; scalar Answer and related-topic snippets are not publisher evidence. General topic references do not establish today's news or observed weather. When no usable source exists, research is labelled unavailable rather than fabricating a verified answer. Direct public HTTPS reading does not send the question to discovery. Publisher restrictions, public-network validation, cancellation and the shared 24-HTTP-request budget remain. Full stored history, memories, other drafts and connected-app text are not sent. Inference, extraction, ranking and answer generation remain on-device.

DuckDuckGo receives the focused public topic and ordinary connection information including your IP address. Visited publishers receive their requested URL and connection information. Their own privacy and retention policies apply. BI uses no cloud answer model, weather API, advertising ID, persistent web cookie or developer-operated search proxy. The documented JSON API is called directly; linked publisher pages and redirects retain publisher-policy checks. Sign-in, paywalls and CAPTCHA are not bypassed. The app displays DuckDuckGo attribution and separate publisher links. [Official publisher API reference](https://github.com/duckduckgo/duckduckgo-publisher/blob/master/share/site/duckduckgo/api.tx), [DuckDuckGo privacy policy](https://duckduckgo.com/privacy).

Automatic research applies to new and existing conversations. Global web permission remains under Web & grounding. Version 23 requires enabling it once after reading the DuckDuckGo-only disclosure (consent 5). Prior permission for other search services does not silently permit DuckDuckGo. Saved source cards from older releases remain accurate historical records; retaining their provider labels does not contact or enable those services. Clicking the DuckDuckGo attribution opens a relevant topic result page in your browser; BI does not scrape that page.

Do not include passwords, API keys, confidential material or other sensitive information in a web-enabled question. A local credential-pattern check blocks some obvious secrets, but cannot identify every kind of private information. Retrieved pages are untrusted evidence. The app labels retrieval failures and supplies citations only for pages it actually retrieved; citations do not prove that a claim is true or current. Some pages cannot be read without scripts, sign-in or a supported text format.

Temporary evidence and query keys are held only in app memory, bounded to eight requests and at most 15 minutes (one minute for weather observations and five minutes for changing facts), and can be cleared in Settings. Source excerpts and links attached to an answer are stored with that conversation until you delete it. Questions, source text and search URLs are excluded from application logs. No research data is automatically uploaded to the developer.

## Connected apps, including TypeRight

Connected-app requests always stay on-device in Better Intelligence and never invoke web grounding. Each app must be explicitly approved and is subject to your permission and rate-limit choices. Access logs record operational metadata; polish input and output are excluded. App approval does not change the other app's own privacy settings. Review the other app's policy for any separate online features it offers.

## Diagnostics and support

Hardware monitoring shows local performance, memory and thermal information. Better Intelligence does not automatically upload chats, keyboard text, local model outputs or hardware benchmark reports to a developer server. If you choose to share diagnostic information, review it first. Questions and deletion/support requests can be sent to siddharthpazhoor@gmail.com or raised through the [project's GitHub issues](https://github.com/sidhrthmnn/BetterIntelligence/issues); do not post secrets or personal text in public issues.
