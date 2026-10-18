# Optional E2B comparison and E4B restoration

HONOR VKP-NX9, Android 16, late 18 October / early 19 October 2026 UK time.
Play-installed TypeRight 215 and BI 2.0 (5) were unchanged. This experiment uses
TypeRight's new JSON selected-span prompt and is separate from the generic
multilingual model study and the earlier version-213 prompt.

## Outcomes, including failures

| Check | Observed outcome | Whole UI test seconds |
|---|---|---:|
| E2B numeric | `i has 2 apples.` → `I have 2 apples.`, insertion and exact undo passed | 9.421 |
| E2B question | Completed review, then expected final text not found; failed, exact applied bytes not captured | 64.025 |
| E2B selected `mesage` | Preview `message send the now`; operator stopped the fixture; no final insertion/undo assertion | Interrupted |
| Restored E4B numeric | Correction, insertion and exact undo passed | 21.215 |

The E2B question's raw UI report records 46,661 ms until the completed review
marker, not its full output. Do not infer the applied text from that marker.
An independent six-request diagnostic probe records all completed raw responses:

| E2B input | Plain / whole-field / whole-selection results | Complete diagnostic request ms |
|---|---|---|
| `i has 2 apples.` | `I have 2 apples.` in all three | 352 / 564 / 577 |
| `what cab you do nos` | `What can you do?` in all three; meaning-bearing `now` omitted | 529 / 430 / 429 |

All six responses were marked as companion responses with `fallback: false`.
The diagnostic JUnit test failed its exact-reference assertion; the three
question outputs are quality misses, not transport failures or guard fallback.
This confirms that trusting completed BI output does not ensure a correct edit.
TypeRight continues to show the response unchanged for user review as requested.

The selected-span input was `send the mesage now`, with offsets 9..15 verified
before inference. Its completed preview reordered the surrounding context.
The fixture was deliberately force-stopped after observing that bad preview;
the raw log's `Process crashed` is this operator interruption, not evidence of
a BI runtime crash. The raw JSON remains `status: running` and is not a pass.
The screenshot contains only synthetic editor content and that preview.

An earlier question attempt failed while exposing Writing tools, before an
inference request. It is retained as `e2b-question-fixture-before.*` and is not
included as an inference-quality failure or pass.

## Limits of the measurements

UI timing includes accessibility polling, review scrolling and interactions.
E2B's selected-span review marker took 181,764 ms, and the restored E4B numeric
marker took 18,701 ms. These are not isolated inference benchmarks. The separate
diagnostic requests have different warm-up and interaction costs. AUTO choices,
memory pressure and thermal history were not held identical across models;
these numbers cannot establish an engine speed ratio or sustained battery cost.

E4B remains the verified option for the narrow installed English samples, with
known errors retained in the larger model study. This does not establish
universal semantic accuracy, a multilingual TypeRight winner or a guarantee
that other requests will preserve meaning. Review remains necessary.

## Restoring the original model after memory pressure

E2B was explicitly unloaded while idle. E4B initially remained disabled because
the current available memory, after BI's reserve, was below its displayed
3,590 MB estimate. Both downloaded files remained intact. CPU and GPU use shared
system RAM; selecting GPU does not supply an extra memory pool.

After the user closed unused apps, `/proc/meminfo` showed 4,671,016 KiB available
before reopening BI. The installed Models UI then reported E4B loaded,
`Ready on this device`, and selected for approved apps. A fresh real installed
numeric correction/insertion/exact-undo test passed. This verifies recovery
after memory was freed, not that the model can load safely under arbitrary
memory pressure. No reserve was bypassed and no large model was downloaded.

The Automatic radio remained selected. The production TypeRight IME was
restored as default after test cleanup; the temporary diagnostic package was
disabled. Wi-Fi and mobile data were both on. No production package was
uninstalled or sideloaded, and no user data or downloaded model was erased.
The follow-up did not independently measure the active CPU/GPU backend.

## Evidence

- `installed-e2b-numeric.*`: installed keyboard pass and raw JUnit output.
- `installed-e2b-question.*`: installed keyboard failed expectation.
- `e2b-companion.*`: service model identity, all six exact outputs and diagnostic failure.
- `installed-e2b-selection.*`, `e2b-selection-preview.png`: interrupted preview experiment.
- `e2b-question-fixture-before.*`: setup-only failure.
- `installed-e4b-restored.*`, `e4b-restoration.json`: successful rollback check and final device-state observations.

The `modelVerifiedInHostUi` field is an operator-supplied label. E2B's independent
companion probe records actual service identity; E4B restoration was observed
directly in installed BI's model UI. Neither is a new automatic model-identity
assertion in the disposable editor. These are report-only additions; published
TypeRight 215 and BI code 5 bundles remain unchanged.
