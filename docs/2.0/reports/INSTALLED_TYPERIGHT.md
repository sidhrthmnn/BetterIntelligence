# Installed TypeRight and Play upgrade validation

Physical HONOR VKP-NX9, Android 16, 18 October 2026. Both production packages were
updated through Google Play, with their original data and approval retained:
BI **2.0 / 5**, TypeRight **215.0 / 215**. No production APK was sideloaded, no
keyboard data was erased, and no model weights are included in TypeRight.

## Diagnosis and fix

The problem persisted with only the production BI app enabled. TypeRight binds
that package explicitly. A synthetic learned-vocabulary reproduction showed the
correct BI output `We have 2 apples.` being rejected by TypeRight's older content
guard, which returned the erroneous original instead. The user's private
dictionary was neither read nor changed for this reproduction.

At the user's explicit request, TypeRight 214 removed its BI-specific wording,
literal, spelling and language vetoes, sanitizer, spelling draft and rule-based
recovery. BI's completed response is now passed unchanged through review and
replacement. Selected text and bounded read-only context are sent as JSON;
TypeRight does not reconstruct and post-filter a whole-sentence model response.
Approval, private fields, incomplete/error responses, cancellation and stale
editor protections remain. This does not grant other apps access to BI.

Play-installed 214 corrected and inserted the sample, but its Writing tools
review closed before the existing overflow undo could be reached. TypeRight 215
connects successful application to the keyboard's existing exact-snapshot undo
action. The 214 partial result remains recorded and is not an undo pass.

## Actual installed-keyboard results

The disposable editor's UI driver operates the installed production keyboard;
it has no Binder inference client or bundled model. The selected model was Gemma
4 E4B LiteRT. The service model identity is independently recorded by the companion
probe. The UI records' `modelVerifiedInHostUi` field is an operator-supplied label,
not an additional automated model-identity assertion.

| Synthetic check | Observed outcome | Whole UI test seconds |
|---|---|---:|
| `i has 2 apples.` | Reviewed `I have 2 apples.`, inserted, exact undo | 10.465 |
| Selected `mesage` in a sentence | Replaced only with `message`, exact undo | 58.692 |
| `what cab you do nos` | Reviewed `What can you do now?`, inserted, exact undo | 7.381 |
| Negation | Correct original unchanged | 64.878 |
| Technical identifier | `com.example.demo` unchanged | 37.981 |
| URL and time | `https://example.com` and `09:15` unchanged | 7.858 |
| Dismiss a long request | Original 999 characters retained | 2.783 |
| BI process restart, no network | Correction, insertion and undo after one manual retry | 150.567 |

All eight checks passed. Review did not insert before the user action; the
original text was restored by undo where applicable. Consecutive requests reused
the installed provider and its selected model. Restart changed the BI process ID
and preserved approval. The offline test asserted there was no active network;
Wi-Fi and mobile data were restored afterward.

The restart initially returned BUSY while BI loaded. The operator tapped the
visible Proofread action once at 23:32:33 UK time. The 146,826 ms review timing
includes that deliberate wait and UI polling; it is not automatic recovery or
an isolated cold-start inference benchmark. Other UI timings also include
accessibility overhead. Separate diagnostic complete requests took
3,992–4,163 ms across six English correction/context combinations, without rule
fallback. Those diagnostic timings are not production UI latency.

Earlier test-fixture failures and interrupted probes are retained in the
[TypeRight release record](https://github.com/sidhrthmnn/TYPERIGHT/blob/f0d5e66b5059daea20833fc06c205d0d41b2bd2d/docs/release-215.md).
Synthetic key injection did not reliably establish selection, and the status
label on a wrapped card needed scrolling. The fixture now sets only its own
native selection and scans/scrolls the actual Android UI. Interrupted fixture
processes are not product crashes or passes. Raw final results are in
[installed-typeright](installed-typeright/).

## Model interpretation and release identity

E4B is the verified TypeRight option for these English samples on this phone.
This narrow result is not a universal proofreading winner or semantic guarantee;
the larger quality study retains E4B errors and false changes. A subsequent E2B
comparison with the new JSON prompt passed the numeric example but dropped
`now` in the question and reordered context in a selected-span preview. The
question failed its expected-output check; the selection experiment was stopped
and is not an insertion/undo pass. E4B was restored after the user freed RAM,
then passed a fresh installed correction/insertion/exact-undo check (21.215 s
whole UI test). See [follow-up results and raw evidence](installed-typeright/FOLLOWUP_MODELS.md).
The new prompt has not been compared across every candidate. Keep review before apply.
The Fast/Balanced/Advanced chat choices remain the separately measured tradeoffs.

BI code 5 was available to internal testers at 21:18 UK time. Its signed AAB is
89,869,992 bytes, SHA-256
`5f71a842bf36e42c0d438989067ea7b90ded95e440140699e0df6975d4630642`,
source `f7980627ee15b44140011d6757eea5e2805f4444`. Follow-up commit
`5c621f3beacfb2541b81903f115d564be73a6390` adds release tests/records;
[CI 39165532465](https://github.com/sidhrthmnn/BetterIntelligence/actions/runs/39165532465)
passed. Later updates here are documentation and synthetic verification records.

TypeRight 215 was available to internal testers at 23:08 and Play-delivered to
the phone at 23:09:30. AAB SHA-256
`610b28b8e686e47fae91125cee3b9f7ce38871cce7ab6c57977caebdc47c1c6c`,
source `dadb39698f6eeea1cd480f3333f7524b4fc7bd02`. Both its source CI and
[main CI](https://github.com/sidhrthmnn/TYPERIGHT/actions/runs/39170845106) passed.
The temporary diagnostic keyboard was disabled after use. The production
keyboard remains the default IME.

The main release merges passed CI: [BI 39173793093](https://github.com/sidhrthmnn/BetterIntelligence/actions/runs/39173793093)
at `18b7893ad88638667675b80d1603626d696d769d`, and
[TypeRight 39173551441](https://github.com/sidhrthmnn/TYPERIGHT/actions/runs/39173551441)
at `417c6c6232e9b51a722f5bdcbb629fdbcea338b8`. Later comparison records do not
alter either published bundle.

Production rollout remains blocked by Play's app-setup/closed-testing gate and
the final Console privacy declarations. Internal publication is confirmed; it
is not a public production release. See [validation](../VALIDATION.md) and
[Data Safety review](../DATA_SAFETY_REVIEW.md).
