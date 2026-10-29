# Better Intelligence privacy policy

Updated 29 October 2026 for version 22 / 2.4.0-beta2.

Developer: HaveAnIdea. Contact: siddharthpazhoor@gmail.com. Android package: com.aistudio.betterintelligence.mobile.

Better Intelligence runs downloaded AI models on your Android device. Chat, proofreading and connected-app inference have no automatic cloud fallback. Review AI-generated text before relying on it.

## Local information and your controls

Conversations, drafts, saved memories, model files and connected-app approvals are stored in the app's private Android storage. You can delete conversations and temporary search data in Settings → Privacy & data, remove models in Settings → Models, and delete memories in Settings → Memory. Deleting a model does not delete conversations. Android backup can include settings and conversations according to your system backup preferences. Model weights and provider credentials are excluded from backup and device transfer.

Memory is off by default. Enabling it lets chat save explicit reminders and brief profile statements and use active saved memories when replying. You can pause or delete individual memories. Connected apps have separate, explicit permissions for inference and reading or writing shared memories. Approving inference alone does not share personal memories. Revoke access in Settings → Connected apps.

## Internet use

Each conversation also keeps an extractive local context summary, separate from optional memory shared across chats. It retains older user statements, early project subjects and newer decisions. If a later user message refers to an assistant proposal, a labelled, unverified quotation of that proposal may also be retained. Notes remain attributed and bounded; this does not guarantee perfect recall. Editing or regenerating older messages invalidates the summary. It survives app restarts/model changes and is removed when the conversation is deleted.

For contextual web follow-ups, up to two short titles from already retrieved public sources in the same conversation can be added to the query. Private stored messages and global memories are not copied to the search provider. Weather uses the same public-page research for a city you type; no weather API, geocoding service, GPS permission or device location is used.

An internet connection is used when you discover or download models from Hugging Face and its download infrastructure. These services receive the network information needed to handle your request, including your IP address and the requested model. Model terms are linked before use where required.

Web access is optional and **requires your permission**. When enabled, automatic research checks current facts, relevant compatibility questions and explicit external-source requests. General explanations, established history, creative/supplied-text tasks, personal context, calculations and explicit offline requests stay local by default. If a public factual answer explicitly says it lacks current information, BI can make one recovery search. Research failure does not disable useful on-device answers, which are labelled separately from source-supported responses. BI starts with one focused search and can make at most three discovery calls for missing evidence derived from the current question (up to 400 characters per query). It uses Exa's official anonymous hosted search service, with Mwmbl's independent anonymous search API as a fallback. Both have shared-IP limits; no unlimited service is promised. Mwmbl's allowance is currently 1,000 searches per month per public IP and approximately one request per second. No API key, sign-in, payment details or paid upgrade is used. Rate limits/denials cause backoff. Search results only discover links; BI reads publisher pages directly and retains up to three sources. Discovery highlights, summaries, dates and headlines alone do not count as evidence. A supplied public HTTPS page is read without sending the question to discovery. Policy reads and redirects share the 24-request budget and 30-second coordinator deadline. Full stored history, memories, other drafts and connected-app text are not sent for research. Extraction, ranking and answer generation remain local.

Search services and visited websites receive your query/requested URL and connection information including your IP address. Exa additionally receives a short retrieval objective derived from the public question and current date. They may infer an approximate location; their own privacy and retention policies apply. Better Intelligence does not send inference prompts or chat history to a cloud answer model and uses no weather API, advertising ID, persistent web cookies or developer-operated search proxy. Exa and Mwmbl only discover links; BI does not invoke Exa Agent or its page-fetch tool. Publisher policies are checked, including before redirects, and sign-in, paywalls and CAPTCHA are not bypassed. [Exa anonymous service and limits](https://github.com/exa-labs/exa-mcp-server#authentication), [Mwmbl service information and anonymous limits](https://developer.mwmbl.org/).

Automatic research applies to existing and new conversations; there is no separate per-conversation Off/On menu. The global web permission remains under Web & grounding. Version 22 requires enabling web research once after reading the updated disclosure naming Exa and Mwmbl; old consent does not silently permit a new destination. Source cards identify the discovery provider where recorded. Old saved source cards remain; DuckDuckGo HTML, Bing feeds and Wikipedia/Brave integrations remain inactive. DuckDuckGo's Instant Answer API was evaluated but is not used by this release.

Do not include passwords, API keys, confidential material or other sensitive information in a web-enabled question. A local credential-pattern check blocks some obvious secrets, but cannot identify every kind of private information. Retrieved pages are untrusted evidence. The app labels retrieval failures and supplies citations only for pages it actually retrieved; citations do not prove that a claim is true or current. Some pages cannot be read without scripts, sign-in or a supported text format.

Temporary evidence and query keys are held only in app memory, bounded to eight requests and at most 15 minutes (one minute for weather observations and five minutes for changing facts), and can be cleared in Settings. Source excerpts and links attached to an answer are stored with that conversation until you delete it. Questions, source text and search URLs are excluded from application logs. No research data is automatically uploaded to the developer.

## Connected apps, including TypeRight

Connected-app requests always stay on-device in Better Intelligence and never invoke web grounding. Each app must be explicitly approved and is subject to your permission and rate-limit choices. Access logs record operational metadata; polish input and output are excluded. App approval does not change the other app's own privacy settings. Review the other app's policy for any separate online features it offers.

## Diagnostics and support

Hardware monitoring shows local performance, memory and thermal information. Better Intelligence does not automatically upload chats, keyboard text, local model outputs or hardware benchmark reports to a developer server. If you choose to share diagnostic information, review it first. Questions and deletion/support requests can be sent to siddharthpazhoor@gmail.com or raised through the [project's GitHub issues](https://github.com/sidhrthmnn/BetterIntelligence/issues); do not post secrets or personal text in public issues.
