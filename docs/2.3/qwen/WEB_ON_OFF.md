# Web OFF / ON measured comparison

Model: **Qwen_Qwen3-1.7B-Q4_K_M.gguf**, VKP-NX9, Android API 36. CPU, 4 threads, context 2048, output 160, temperature 0. Same weights, context, sampling and per-case history; alternating pair order, one run. Model reused; fresh conversation per answer.

Network time is separate from model time. Research failure/clarification deliberately skips inference, as in the app. Direct source URLs occur in both variants; they may hint at an answer even offline. The optional correction pass is disabled for this controlled comparison. Charging and uncontrolled network prevent sustained/energy conclusions. PSS excludes some driver allocations.

Quality is explicitly reviewed against independent reference snapshots (0 wrong/unsupported/failure, 1 useful but incomplete or appropriate uncertainty, 2 correct and sufficiently complete/appropriate clarification). An abstention is never credited as a correct factual answer. These are limited agent reference reviews, not independent human ratings or a universal accuracy estimate. Missing reviews remain unscored.

| Case | Web OFF (actual excerpt) | Web ON (actual excerpt) | Reviewed quality winner | Total latency winner |
| --- | --- | --- | --- | --- |
| 2: What is 15% of 240? Return the result briefly. | 15% of 240 is 36. (5.58s) | 15% of 240 is 36. (5.63s) | Tie | OFF |
| 5: Who is the current UK Prime Minister? | I haven't checked live sources, so I can't confirm a current name, result or number for this question. Enable  (8.32s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (20.93s) | Tie | OFF |
| 9: Compare LiteRT-LM and llama.cpp for Android formats and hardware acceleration. | LiteRT-LM and Llama.cpp are both implementations of the LLaMA model, but they differ in their design, performa (17.37s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (46.35s) | ON | OFF |
| 16: Find proof of the fictional 2026 transfer of example.com to Orion Moon Corporation. | The request appears to be based on a fictional scenario, as there is no known historical or current record of  (16.51s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (19.48s) | ON | OFF |
| 20: ഇപ്പോൾ യുകെയുടെ പ്രധാനമന്ത്രി ആരാണ്? മലയാളത്തിൽ ചുരുക്കി പറയുക. | തത്സമയ സ്രോതസ്സുകൾ പരിശോധിച്ചിട്ടില്ല; അതിനാൽ നിലവിലെ പേര്, ഫലം, സംഖ്യ എന്നിവ ഉറപ്പിക്കാൻ കഴിയില്ല. പൊതുസ്രോതസ (14.05s) | I couldn't verify an answer from relevant web sources. Try a more specific question or provide a public HTTPS  (48.03s) | Tie | OFF |
| 21: Work offline without search. Extract this as JSON only with string keys id and enabled: identifier 001; enabled false. Preserve the leading zeros and boolean type. | {"id": "001", "enabled": false} (8.57s) | {"id": "001", "enabled": false} (8.52s) | Tie | ON |

## Aggregate measurements

```json
{
  "OFF": {
    "answers": 6,
    "httpRequests": 0,
    "medianTotalMs": 11306.0,
    "totalRetrievalMs": 0,
    "totalInferenceMs": 68989,
    "modelInvocations": 6,
    "medianGeneratedTtftMs": 5985.0,
    "medianGeneratedTokensPerSecond": 15.214387893676758,
    "maximumSampledProcessPssMb": 1710.1318359375,
    "maximumAndroidThermalStatus": 1,
    "failures": 0,
    "researchUnavailableAnswers": 0,
    "insufficientEvidenceAnswers": 0,
    "reviewed": 6,
    "reviewQualityPoints": 6,
    "reviewMaxPoints": 12,
    "reviewedCorrectCompleteAnswers": 2,
    "reviewedClarifications": 0,
    "reviewedSafeAbstentions": 2,
    "unsupportedClaimsObserved": 4
  },
  "ON": {
    "answers": 6,
    "httpRequests": 10,
    "medianTotalMs": 20203.5,
    "totalRetrievalMs": 4344,
    "totalInferenceMs": 141055,
    "modelInvocations": 6,
    "medianGeneratedTtftMs": 14645.5,
    "medianGeneratedTokensPerSecond": 14.174156188964844,
    "maximumSampledProcessPssMb": 1778.9716796875,
    "maximumAndroidThermalStatus": 1,
    "failures": 0,
    "researchUnavailableAnswers": 0,
    "insufficientEvidenceAnswers": 4,
    "reviewed": 6,
    "reviewQualityPoints": 8,
    "reviewMaxPoints": 12,
    "reviewedCorrectCompleteAnswers": 2,
    "reviewedClarifications": 0,
    "reviewedSafeAbstentions": 4,
    "unsupportedClaimsObserved": 0
  }
}
```

Per-category review points (maximum two per reviewed answer):

| Category | OFF points / reviewed maximum | ON points / reviewed maximum |
| --- | --- | --- |
| changing | 1 / 2 | 1 / 2 |
| format | 2 / 2 | 2 / 2 |
| malayalam | 1 / 2 | 1 / 2 |
| reliability | 0 / 2 | 1 / 2 |
| static | 2 / 2 | 2 / 2 |
| technical | 0 / 2 | 1 / 2 |
