# Simpler Android screens

The model-browser prototype is in [../prototypes/model-browser/index.html](../prototypes/model-browser/index.html).
Open it in a browser; it has no external dependencies. Device values and downloads
in that prototype are simulated. The Android screens use real app state.

Phone captures from the isolated validation installation: [models](screenshots/models-light.png) and [web research](screenshots/web-dark.png).

## Shared design

Blue primary actions, quiet outlined cards, consistent rounded corners, readable
type, and two appearances: light and true-black dark. The existing animated BI
avatar and light-mode asset remain. Android font/display scaling is preserved.
Main navigation, the single keyboard-inset owner, chat scrolling and drafts are
unchanged.

## Screens

* **Chat:** a shorter model selector, a compact web-research control, a bordered
  composer and consistent source cards. Verified source links and optional image
  consent remain. Long replies expand within the model's existing context.
* **Models:** available RAM and import at the top; three clear tabs; cards show
  file size, estimated RAM, compatibility and one main action. Completed files say
  “Downloaded”, independently of whether current RAM is sufficient to run them.
  Fallback selection, file verification and deletion are in model details.
* **Find models:** repository results are cards with publisher/download metadata
  and a Download action. A version sheet shows actual file sizes, quantization,
  RAM estimates, checksum availability and the publisher's licence link. RAM
  pressure does not disable downloading a model for later use.
* **Connected apps:** readiness and per-app access cards; setup instructions and
  developer material are expandable. Approval, inference/memory permissions,
  blocking and rate limits keep their existing behaviour.
* **Settings/history:** grouped destination cards and readable conversation cards.
  Rename, delete and back navigation retain their original actions.
* **Appearance:** light and dark choice cards, no additional legacy dark mode.
* **Web research:** the switch, visible network/privacy disclosure and three short
  steps. Limits, data retention and diagnostics remain under Privacy and tools.
* **Memory:** separate cards for per-chat context and optional cross-chat memory.
* **Privacy:** local-data summary, expandable internet/backup explanation, and
  explicit data-management actions with existing deletion confirmations.
* **Advanced:** automatic processing and reply-length summaries; expandable
  hardware, compatibility and benchmark details; app information and licences.

No download transport, model-integrity checks, app approval, Binder contract,
source permissions or data-deletion safeguards are relaxed by these UI changes.
Memory numbers are estimates, not an acceleration or runtime-compatibility claim.

## Automatic reply budgets

Chat starts with a useful bounded output allocation and can grow up to 1,024
tokens. GGUF expands the running decode loop when its initial budget is exhausted,
using the native template's exact input-token count and remaining context. It
does not replay the prompt or switch models. EOS ends normally, even after the
initial limit was crossed. Cancellation, the 180-second deadline, and memory and
thermal checks still apply to every generated token.

LiteRT-LM cannot change an already-running Conversation's output configuration.
BI reserves additional headroom before that conversation starts, only when a
conservative UTF-8 input/history bound leaves room. It does not claim that this
bound is an exact tokenizer measurement. The allocated model context is not
increased. Connected-app polishing, fixed-length requests and backend benchmarks
keep their explicit limits; expansion is opt-in for the chat path.

The physical Qwen 3.5 2B CPU check produced 16 tokens with the fixed budget and
189 tokens with expansion, finishing the requested 1–50 sequence without a
duplicated prefix. That measures continuation correctness, not GPU/NPU speed.

## Validation

`SimpleUiDeviceTest` visits all ten destinations in both appearances and checks
that research tools remain reachable. `ModelDiscoveryCardUiTest` uses deterministic
callbacks at 1.3× font scale to check search, version selection and a download
under low available RAM, without downloading a live file. Existing navigation,
model-download ordering and integrity tests are retained. Final execution results
are recorded in [the validation report](../research/VALIDATION.md).
