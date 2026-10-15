# Simpler UI and shared local AI

The app now starts with model setup: a recommendation, download or import, and one Use model action. Runtime readiness comes from the engine. Chat directs users to setup when no model is loaded. Hardware diagnostics, benchmarks and model discovery are available under Advanced tools rather than preceding the model list.

The Apps screen explains how to connect a consumer and shows actual access requests. Each app can be approved, blocked or rate-limited. AI inference and memory sharing have separate permissions; new apps do not receive memory access. Settings focuses on appearance, privacy, saved memories and clearing local data. The theme choice is persisted, and system bar icons follow the app theme.

AI Polish is available through a single-file ContentResolver client and through the existing Binder contract. Responses distinguish approval, model setup, busy, rate-limited and error states from successful replacement text. A separate sample app proves the connection without internet permission or model weights. The Android selection/share action offers a user-mediated alternative with a review step.

The implementation also repairs missing AIDL methods and repository/engine methods that prevented compilation. System and user messages now keep their roles through the native model template, rather than being flattened into one user turn. Caller identity comes from Android's UID, including the legacy provider routes; a client-supplied package cannot bypass approval. Access logs discard all prompt and response text. The Binder client dispatches generation to IO, releases pending bindings, and avoids cancelling another request after its stream has completed. Hardware analysis runs off the UI thread.

## Verification

- All 24 JVM tests pass, including real GGUF metadata, approval, scopes, rate limits, private logs and polish literal-preservation checks.
- Debug APKs build for the main app, sample client and their instrumentation tests. Native ARM64 and x86-64 runtimes compile.
- Separate-UID emulator tests pass for pending approval with an attempted identity spoof, approved provider and Binder polish, blocked access, disabled inference and rate limiting.
- Provider and Binder polish also pass with emulator Wi-Fi and mobile data disabled, using the pinned Qwen 2.5 0.5B model.

These are CPU/emulator checks, not measurements on an ARM phone. Writing quality depends on the selected general-purpose model. A preview is required; literal checks do not guarantee that meaning or tone is preserved. Typeright's own repository is unchanged: its AI provider selection and editor-review integration can use [the supplied client and contract](APP_INTEGRATION.md).
