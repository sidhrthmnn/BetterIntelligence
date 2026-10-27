# Web OFF / ON measured comparison

Model: **gemma-4-E2B-it.litertlm**, VKP-NX9, Android API 36. CPU, 4 threads, context 2048, output 160, temperature 0. Same weights, context, sampling and per-case history; alternating pair order, one run. Model reused; fresh conversation per answer.

Network time is separate from model time. Research failure/clarification deliberately skips inference, as in the app. Direct source URLs occur in both variants; they may hint at an answer even offline. The optional correction pass is disabled for this controlled comparison. Charging and uncontrolled network prevent sustained/energy conclusions. PSS excludes some driver allocations.

Quality is explicitly reviewed against independent reference snapshots (0 wrong/unsupported/failure, 1 useful but incomplete or appropriate uncertainty, 2 correct and sufficiently complete/appropriate clarification). An abstention is never credited as a correct factual answer. These are limited agent reference reviews, not independent human ratings or a universal accuracy estimate. Missing reviews remain unscored.

| Case | Web OFF (actual excerpt) | Web ON (actual excerpt) | Reviewed quality winner | Total latency winner |
| --- | --- | --- | --- | --- |
| 1: Explain recursion with a short example. | ## Recursion Explained  **Recursion** is a programming technique where a function calls itself in order to sol (8.35s) | Recursion is when a function calls itself [1].  When written carefully, recursion is a clean way to solve prob (11.64s) | Tie | OFF |
| 2: What is 15% of 240? Return the result briefly. | 36 (0.92s) | 36 (0.93s) | Tie | OFF |
| 3: Explain the difference between RAM and storage. | The difference between RAM (Random Access Memory) and storage is fundamentally about **speed, volatility, and  (6.71s) | Memory is another term for RAM [1].  Some details were omitted because the supplied evidence did not support t (11.00s) | OFF | OFF |
| 4: Explain Android's activity lifecycle briefly. | Android's Activity Lifecycle defines the various states an `Activity` goes through from when it is created unt (6.79s) | The activity lifecycle introduces the relationship between activity state and a process's vulnerability to bei (8.65s) | OFF | OFF |
| 5: Who is the current UK Prime Minister? | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (2.68s) | The current Prime Minister is The Rt Hon Andy Burnham MP [1].  Andy Burnham became Prime Minister on 20 July 2 (3.72s) | ON | OFF |
| 6: Who is the current CEO of Microsoft? | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (3.28s) | Satya Nadella is the Chairman and Chief Executive Officer [1]. (7.33s) | ON | OFF |
| 7: What is the latest stable major Android version? | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (3.31s) | I couldn't find enough relevant evidence to answer this question. Try a more specific question or a public HTT (2.84s) | Tie | ON |
| 8: Who won the 2026 Nobel Prize in Physics? | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (2.69s) | Francis Halzen is awarded the Nobel Prize in Physics 2026 [1]. (4.62s) | ON | OFF |
| 9: Compare LiteRT-LM and llama.cpp for Android formats and hardware acceleration. | Here is a comparison of LiteRT-LM and llama.cpp for Android formats and hardware acceleration, based on the in (7.39s) | LiteRT-LM for Android has features like GPU and NPU acceleration [2].  llama.cpp supports custom CUDA kernels  (11.74s) | ON | OFF |
| 10: Which hardware acceleration backends does LiteRT-LM support on Android? | I do not have the capability to directly access external websites or specific URLs like the one you provided t (3.92s) | LiteRT-LM for Android supports GPU and NPU acceleration [1]. (9.34s) | Tie | OFF |
| 11: Compare Gemma 4 E2B and Qwen 3 models for mobile memory and performance; state missing comparable measurements. | I do not have access to the specific, detailed performance benchmarks or internal memory specifications for th (7.17s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (12.65s) | Tie | OFF |
| 12: Explain recent Android on-device AI development capabilities. | Based on the provided link to the Google Developers documentation regarding LiteLLM on Android, here is an exp (6.60s) | For an Android sample, one can check out the Google AI Edge Gallery app, which is available on Google Play [1] (12.44s) | ON | OFF |
| 13: Compare Pixel 10 and Pixel 10 Pro RAM and processor using official specifications. | I do not have access to the live content of external websites, including the specific Google Support page you  (4.55s) | The sources could not be read. They may be restricted or temporarily unavailable. Try another public HTTPS sou (0.84s) | Tie | ON |
| 14: Compare LiteRT-LM versus llama.cpp supported model file formats and runtime integration. | Here is a comparison of LiteRT-LM and llama.cpp regarding supported model file formats and runtime integration (7.17s) | LiteRT-LM supports a Kotlin API for Android and JVM (Linux, macOS, Windows) with features like GPU and NPU acc (12.20s) | ON | OFF |
| 15: Is GGUF or LiteRT-LM always faster and more memory efficient on Android? Distinguish measured device conditions from unsupported conclusions. | Whether GGUF or LiteRT-LM is *always* faster and more memory-efficient on Android depends heavily on the speci (7.83s) | *   The backend is CPU [2].  Some details were omitted because the supplied evidence did not support them. (14.01s) | OFF | OFF |
| 16: Find proof of the fictional 2026 transfer of example.com to Orion Moon Corporation. | I do not have access to real-time legal documents, private corporate filings, or specific, unverified claims r (6.67s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (5.92s) | Tie | ON |
| 17: What is the exact current sale price in pounds of example.com? Do not invent an unavailable number. | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (4.86s) | I couldn't find enough relevant evidence to answer this question. Try a more specific question or a public HTT (0.43s) | Tie | ON |
| 18: Who owns it? | Please provide more context. What are you referring to? (1.29s) | Which product, organisation or item do you mean? Please name it so I can check the right subject. (0.05s) | Tie | ON |
| 19: Does that runtime support GPU? | Based on the provided link to the Google Developers documentation for LiteRT-LM on Android, I do not have dire (8.16s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (7.75s) | Tie | ON |
| 20: ഇപ്പോൾ യുകെയുടെ പ്രധാനമന്ത്രി ആരാണ്? മലയാളത്തിൽ ചുരുക്കി പറയുക. | തത്സമയ സ്രോതസ്സുകൾ പരിശോധിച്ചിട്ടില്ല; അതിനാൽ നിലവിലെ പേര്, ഫലം, സംഖ്യ എന്നിവ ഉറപ്പിക്കാൻ കഴിയില്ല. പൊതുസ്രോതസ (6.70s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (6.80s) | Tie | OFF |
| 21: Work offline without search. Extract this as JSON only with string keys id and enabled: identifier 001; enabled false. Preserve the leading zeros and boolean type. | {   "id": "001",   "enabled": false } (2.51s) | {   "id": "001",   "enabled": false } (2.50s) | Tie | ON |
| 22: Rewrite locally without web search, preserving the negation and Manglish in Latin letters: njan innu varilla, nale varam. | Njan innu varilla, nale varam. (1.28s) | Njan innu varilla, nale varam. (1.27s) | Tie | ON |

## Aggregate measurements

```json
{
  "OFF": {
    "answers": 22,
    "httpRequests": 0,
    "medianTotalMs": 5730.5,
    "totalRetrievalMs": 1,
    "totalInferenceMs": 106213,
    "modelInvocations": 22,
    "medianGeneratedTtftMs": 1249.0,
    "medianGeneratedTokensPerSecond": 26.67818260192871,
    "maximumSampledProcessPssMb": 1724.830078125,
    "maximumAndroidThermalStatus": 0,
    "failures": 0,
    "researchUnavailableAnswers": 0,
    "insufficientEvidenceAnswers": 0,
    "reviewed": 22,
    "reviewQualityPoints": 23,
    "reviewMaxPoints": 44,
    "reviewedCorrectCompleteAnswers": 3,
    "reviewedClarifications": 1,
    "reviewedSafeAbstentions": 10,
    "unsupportedClaimsObserved": 4
  },
  "ON": {
    "answers": 22,
    "httpRequests": 61,
    "medianTotalMs": 7066.5,
    "totalRetrievalMs": 47891,
    "totalInferenceMs": 86251,
    "modelInvocations": 18,
    "medianGeneratedTtftMs": 2439.0,
    "medianGeneratedTokensPerSecond": 27.03320026397705,
    "maximumSampledProcessPssMb": 1716.888671875,
    "maximumAndroidThermalStatus": 0,
    "failures": 0,
    "researchUnavailableAnswers": 3,
    "insufficientEvidenceAnswers": 4,
    "reviewed": 22,
    "reviewQualityPoints": 26,
    "reviewMaxPoints": 44,
    "reviewedCorrectCompleteAnswers": 6,
    "reviewedClarifications": 1,
    "reviewedSafeAbstentions": 7,
    "unsupportedClaimsObserved": 0
  }
}
```

Per-category review points (maximum two per reviewed answer):

| Category | OFF points / reviewed maximum | ON points / reviewed maximum |
| --- | --- | --- |
| changing | 4 / 8 | 7 / 8 |
| comparison | 2 / 6 | 2 / 6 |
| followup | 1 / 2 | 1 / 2 |
| format | 2 / 2 | 2 / 2 |
| language | 2 / 2 | 2 / 2 |
| malayalam | 1 / 2 | 1 / 2 |
| reliability | 4 / 6 | 4 / 6 |
| static | 5 / 8 | 3 / 8 |
| technical | 2 / 8 | 4 / 8 |
