# Better Intelligence 2.0 work log and release gates

Development branch: `codex/better-intelligence-2-0`. Baseline: `acc893d` (Play 1.3 / 4).

## Screen audit and design

The baseline launches into Models and has four permanent tabs. Chat exposes sampling controls and performance counters, has a single flat history, and keeps its draft only in composition. Settings, model discovery, memory, access policies, diagnostics and benchmarks must remain accessible. The existing single-owner IME inset and respectful streaming scroll are important behaviours to retain.

The new navigation starts with Chat and opens History and Settings as separate screens. Models, Connected Apps, Web, Appearance, Memory, Privacy and Advanced are settings destinations. Existing diagnostic tools remain in Advanced. No inference counters appear in normal chat. The supplied smiling brain remains the launcher identity; restrained violet accents connect it to the interface.

Design tokens: 4/8/12/16/24/32 dp spacing; 48 dp minimum actions; 16 sp body with normal Android font scaling; 24 dp composer corners; 12 dp user-message corners. Light uses near-white and charcoal, dark uses neutral charcoal, AMOLED uses black. Use Material semantic colours, not hardcoded text colours. Main content is bounded on wide layouts. Long messages, tables and code must scroll without clipping. No fixed-height text rows.

`mockups.html` is the original design reference made before final Compose implementation. It covers Chat, History, Settings, Models, Connected Apps and Web. Synthetic content only.

## Implementation and validation gates

- [x] Conversation migration from schema 7, preserved approvals and downloads, per-conversation drafts and safe cancellation (automated migration/process tests; final Play upgrade remains below).
- [x] Chat-first navigation, markdown/actions, complete settings routes and three themes.
- [x] Opt-in Wikipedia grounding, secure optional Brave configuration, citations and failure states; no external-client search path.
- [x] Pinned candidate manifest, verified downloads and per-model format/runtime/license checks; unavailable exports explicitly documented.
- [x] CPU/GPU memory/context/cache improvements, measured backend behaviour and cancellation/switching. LiteRT GPU works on HONOR; GGUF Vulkan safely falls back after this driver's pipeline failure.
- [x] 791 proofreading requests, 84 separately reviewed chat responses and physical HONOR model measurements; see `reports/README.md` for limits.
- [x] Actual installed TypeRight 215 end-to-end synthetic tests; eight passed, with one explicit retry in the offline restart case (diagnostic clients are supplemental).
- [x] JVM/instrumentation/lint/release checks, Play upgrade and lifecycle/accessibility screenshots.
- [x] Validated signed AAB and source CI. Main merge/CI and `v2.0` release point are recorded by Git.
- [x] Internal Play-delivered update on HONOR and production-package TypeRight regression, preserving existing data.
- [ ] Final Console privacy/Data Safety declarations and publicly accessible updated privacy policy before production.
- [ ] Production submission only after every mandatory release gate passes; report actual Console status.

On 18 October, Console showed production access locked: closed testing must have at least 12 opted-in testers for 14 days (currently 0). Internal **Testers** is selected with 25 list members; membership is not evidence of closed-test opt-in. Internal testing is available while production access remains gated.

Wikipedia is the user-approved no-key provider. Brave is optional user-supplied-key configuration. Search is off by default. Connected-app inference stays local-only. No model quality winners or benchmark values will be invented.
