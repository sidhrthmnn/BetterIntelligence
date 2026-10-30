# Research follow-through — version 25

The user's latest main commit (`044b87e`) was pulled before this change. Inspection of three conversations on the connected phone identified distinct problems: a public role lookup discarded useful reporting, a weather clarification lost the original task, and a live transit question was answered locally. No chat database or private UI dump is included here.

## Behaviour

- Public fact lookups, live service status and explicit website requests start research before local generation can refuse. Explanations, writing, private context, calculations, explicit offline instructions and disabled web permission retain their local behaviour.
- A weather question followed by a user-supplied place and an affirmative reply retains the original task and place, including conversations saved by an older build. The assistant's suggested city is never treated as the user's location. Changed tasks are not mistaken for place names.
- When useful publisher bodies are available but the full question cannot be settled, a brief source report retains up to two publishers' complete passages with citations and an incomplete-answer label. It does not invent a definitive conclusion. Search highlights, headline-only pages, betting markets and fan theories cannot establish casting facts. Conflicting values remain attributed rather than combined.
- The reader removes link-dominated navigation and publisher subscription controls. Report bodies and short official service-status values rank ahead of related-story links and station lists. Explicit weather dates must match the requested day; an updated timestamp cannot turn next week's forecast into today's. Temperature units such as `16C` are recognised.
- Retrieval remains Exa-only, bounded and key-free, with BI's own public-page reader. Existing consent 6 remains valid. Connected-app inference, TypeRight polishing, AIDL/Binder and native model backends are unchanged.

## Validation

Final build and phone results are recorded below after completion. The opt-in `ResearchFollowThroughDeviceTest` runs in the separate validation package and never opens production chats or models. It checks public role reporting, live transit status, an explicit website follow-up, an older saved weather clarification, a direct raincoat question and the previously failing Doom casting question. Useful source retrieval, substantive passages and complete chat state are tested separately from universal real-world accuracy.

Remaining limits: public pages can be blocked, stale, ambiguous or wrong; some questions have no confirmed answer. The source-report fallback quotes what a publisher actually says and labels the missing conclusion. These checks do not establish that every factual question will be routed correctly or every generated claim will be true. No new GPU/NPU performance claim is made by this release.
