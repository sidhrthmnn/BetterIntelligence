# Better Intelligence 2.0 work log and release gates

Development branch: `codex/better-intelligence-2-0`. Baseline: `acc893d` (Play 1.3 / 4).

## Screen audit and design

The baseline launches into Models and has four permanent tabs. Chat exposes sampling controls and performance counters, has a single flat history, and keeps its draft only in composition. Settings, model discovery, memory, access policies, diagnostics and benchmarks must remain accessible. The existing single-owner IME inset and respectful streaming scroll are important behaviours to retain.

The new navigation starts with Chat and opens History and Settings as separate screens. Models, Connected Apps, Web, Appearance, Memory, Privacy and Advanced are settings destinations. Existing diagnostic tools remain in Advanced. No inference counters appear in normal chat. The supplied smiling brain remains the launcher identity; restrained violet accents connect it to the interface.

Design tokens: 4/8/12/16/24/32 dp spacing; 48 dp minimum actions; 16 sp body with normal Android font scaling; 24 dp composer corners; 12 dp user-message corners. Light uses near-white and charcoal, dark uses neutral charcoal, AMOLED uses black. Use Material semantic colours, not hardcoded text colours. Main content is bounded on wide layouts. Long messages, tables and code must scroll without clipping. No fixed-height text rows.

`mockups.html` is the original design reference made before final Compose implementation. It covers Chat, History, Settings, Models, Connected Apps and Web. Synthetic content only.

## Implementation and validation gates

- [ ] Conversation migration from schema 7, preserved approvals and downloads, per-conversation drafts and safe cancellation.
- [ ] Chat-first navigation, markdown/actions, complete settings routes and three themes.
- [ ] Opt-in Wikipedia grounding, secure optional Brave configuration, citations and failure states; no external-client search path.
- [ ] Pinned candidate manifest, verified downloads and per-model format/runtime/license checks.
- [ ] CPU/GPU memory/context/cache improvements, measured backend behaviour and cancellation/switching.
- [ ] At least 100 proofreading cases, separate chat quality and physical HONOR model results.
- [ ] Actual installed TypeRight end-to-end synthetic tests (diagnostic clients are supplemental).
- [ ] JVM/instrumentation/lint/release checks, upgrade and lifecycle/accessibility screenshots.
- [ ] Validated signed AAB, protected merge/main CI and tag.
- [ ] Internal Play-delivered update on HONOR, production-package TypeRight regression and privacy declarations.
- [ ] Production submission only after every mandatory release gate passes; report actual Console status.

Wikipedia is the user-approved no-key provider. Brave is optional user-supplied-key configuration. Search is off by default. Connected-app inference stays local-only. No model quality winners or benchmark values will be invented.
