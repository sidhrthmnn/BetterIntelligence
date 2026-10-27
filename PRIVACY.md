# Better Intelligence privacy policy

Updated 26 October 2026 for version 2.3 closed beta.

Developer: HaveAnIdea. Contact: siddharthpazhoor@gmail.com. Android package: com.aistudio.betterintelligence.mobile.

Better Intelligence runs downloaded AI models on your Android device. Chat, proofreading and connected-app inference have no automatic cloud fallback. Review AI-generated text before relying on it.

## Local information and your controls

Conversations, drafts, saved memories, model files and connected-app approvals are stored in the app's private Android storage. You can delete conversations and temporary search data in Settings → Privacy & data, remove models in Settings → Models, and delete memories in Settings → Memory. Deleting a model does not delete conversations. Android backup can include settings and conversations according to your system backup preferences. Model weights and provider credentials are excluded from backup and device transfer.

Memory is off by default. Enabling it lets chat save explicit reminders and brief profile statements and use active saved memories when replying. You can pause or delete individual memories. Connected apps have separate, explicit permissions for inference and reading or writing shared memories. Approving inference alone does not share personal memories. Revoke access in Settings → Connected apps.

## Internet use

Each conversation also keeps an extractive local context summary, separate from optional memory shared across chats. It retains older user statements, early project subjects and newer decisions. If a later user message refers to an assistant proposal, a labelled, unverified quotation of that proposal may also be retained. Notes remain attributed and bounded; this does not guarantee perfect recall. Editing or regenerating older messages invalidates the summary. It survives app restarts/model changes and is removed when the conversation is deleted.

For contextual web follow-ups, up to two short titles from already retrieved public sources in the same conversation can be added to the query. Private stored messages and global memories are not copied to the search provider. Weather uses the same public-page research for a city you type; no weather API, geocoding service, GPS permission or device location is used.

An internet connection is used when you discover or download models from Hugging Face and its download infrastructure. These services receive the network information needed to handle your request, including your IP address and the requested model. Model terms are linked before use where required.

Web research is optional and **off by default**. When enabled, the app can make up to three focused searches derived from your current question (up to 400 characters per query, including a year for relative-date questions) to DuckDuckGo's public HTML search page over HTTPS. This total includes any broader date-filter retry for empty results. BI parses links itself and reads up to eight publisher pages directly, retaining up to three relevant sources. It may follow one relevant same-origin article link per research request. A supplied public HTTPS page is read without sending the question to a search engine. All production HTTP calls, including policy reads and redirects, share a limit of 24 requests per research operation, and the chat coordinator applies a 30-second deadline. Your full conversation history, saved memories, other drafts and connected-app text are not sent for research. Inference, passage extraction, relevance ranking and answer generation run locally.

Search services and visited websites receive the information required to serve the request, including your IP address and requested URL. They may infer an approximate location from network information; their own privacy and retention policies apply. Better Intelligence uses no search/grounding/weather API, API key, sign-in, advertising ID, persistent web cookies or developer-operated search proxy. It checks robots policies, including before redirect targets are read, and does not bypass sign-in, paywalls or CAPTCHA. [DuckDuckGo privacy policy](https://duckduckgo.com/privacy).

Automatic mode searches questions that appear to need current facts or verification; On mode requests evidence for the question. Search remains subject to the global permission and can be turned off for a conversation. Users upgrading from Bing feeds or Wikipedia/Brave must enable web research again after reading the updated destination disclosure. Old saved source cards remain in existing conversations, but the old provider integrations are no longer active.

Do not include passwords, API keys, confidential material or other sensitive information in a web-enabled question. A local credential-pattern check blocks some obvious secrets, but cannot identify every kind of private information. Retrieved pages are untrusted evidence. The app labels retrieval failures and supplies citations only for pages it actually retrieved; citations do not prove that a claim is true or current. Some pages cannot be read without scripts, sign-in or a supported text format.

Temporary evidence and query keys are held only in app memory, bounded to eight requests and five minutes, and can be cleared in Settings. Source excerpts and links attached to an answer are stored with that conversation until you delete it. Questions, source text and search URLs are excluded from application logs. No research data is automatically uploaded to the developer.

## Connected apps, including TypeRight

Connected-app requests always stay on-device in Better Intelligence and never invoke web grounding. Each app must be explicitly approved and is subject to your permission and rate-limit choices. Access logs record operational metadata; polish input and output are excluded. App approval does not change the other app's own privacy settings. Review the other app's policy for any separate online features it offers.

## Diagnostics and support

Hardware monitoring shows local performance, memory and thermal information. Better Intelligence does not automatically upload chats, keyboard text, local model outputs or hardware benchmark reports to a developer server. If you choose to share diagnostic information, review it first. Questions and deletion/support requests can be sent to siddharthpazhoor@gmail.com or raised through the [project's GitHub issues](https://github.com/sidhrthmnn/BetterIntelligence/issues); do not post secrets or personal text in public issues.
