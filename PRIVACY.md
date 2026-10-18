# Better Intelligence privacy policy

Updated 18 October 2026 for version 2.0.

Better Intelligence runs downloaded AI models on your Android device. Chat, proofreading and connected-app inference have no automatic cloud fallback. Review AI-generated text before relying on it.

## Local information and your controls

Conversations, drafts, saved memories, model files and connected-app approvals are stored in the app's private Android storage. You can delete conversations and temporary search data in Settings → Privacy & data, remove models in Settings → Models, and delete memories in Settings → Memory. Deleting a model does not delete conversations. Android backup can include settings and conversations according to your system backup preferences. Model weights and provider credentials are excluded from backup and device transfer.

Memory is off by default. Enabling it lets chat save explicit reminders and brief profile statements and use active saved memories when replying. You can pause or delete individual memories. Connected apps have separate, explicit permissions for inference and reading or writing shared memories. Approving inference alone does not share personal memories. Revoke access in Settings → Connected apps.

## Internet use

An internet connection is used when you discover or download models from Hugging Face and its download infrastructure. These services receive the network information needed to handle your request, including your IP address and the requested model. Model terms are linked before use where required.

Web grounding is optional and **off by default**. When you enable it, a web-enabled chat question is sent over HTTPS to the provider you choose: Wikipedia (Wikimedia) or Brave Search. This includes the text of your current question, not your full chat history, saved memories, drafts in other conversations or connected-app text. Automatic mode searches questions that appear to need current facts or verification; On mode requests evidence for the question. You can turn search off globally or for a conversation. Do not include passwords, API keys, confidential material or other sensitive information in a web-enabled question. A local credential-pattern check can block some obvious secrets, but cannot identify every kind of private information.

Wikipedia provides encyclopedia summaries and article links. Brave provides search excerpts and links using an optional API key you supply. The app labels search failures and missing citations; retrieving a page or excerpt does not independently verify every claim. Provider terms and privacy policies apply to the requests they receive: [Wikimedia privacy policy](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy), [Brave Search API privacy information](https://brave.com/search/api/).

Temporary search results and query keys are cached in app memory for up to five minutes and can be cleared in Settings. Source excerpts and links attached to an answer are saved with that conversation until you delete it. A Brave API key is encrypted with a non-exportable Android Keystore key; it is not included in application logs or backups. You can remove it in Web & grounding settings. The app has no HTTP request logging for searches.

## Connected apps, including TypeRight

Connected-app requests always stay on-device in Better Intelligence and never invoke web grounding. Each app must be explicitly approved and is subject to your permission and rate-limit choices. Access logs record operational metadata; polish input and output are excluded. App approval does not change the other app's own privacy settings. Review the other app's policy for any separate online features it offers.

## Diagnostics and support

Hardware monitoring shows local performance, memory and thermal information. Better Intelligence does not automatically upload chats, keyboard text, local model outputs or hardware benchmark reports to a developer server. If you choose to share diagnostic information, review it first. Questions and requests can be raised through the [project's GitHub issues](https://github.com/sidhrthmnn/BetterIntelligence/issues); do not post secrets or personal text in public issues.
