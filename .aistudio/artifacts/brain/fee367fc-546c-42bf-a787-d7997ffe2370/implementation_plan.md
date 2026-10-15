# Better Intelligence: On-Device AI Brain for Android Apps

Transform **Better Intelligence** into a universal, privacy-first **System AI Brain** that any installed Android application can invoke via low-latency Binder IPC, zero-SDK ContentProvider queries, or system-wide Android Intents—complete with shared cross-app RAG memory, structured JSON extraction, custom per-app personas, and granular permission governance.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and product choices have been confirmed from your selections and govern this implementation:

- **Confirmed Decision 1 — Multi-Protocol Inter-App Connectivity**: External Android apps can connect to Better Intelligence through three native Android channels:
  1. **AIDL Binder Service** for low-latency token streaming and full RPC control.
  2. **Zero-SDK ContentProvider** (`content://com.aistudio.localaicore.provider/...`) so any app can query completions, structured JSON, or shared memory using standard `ContentResolver.query()` and `call()` without bundling AIDL stubs.
  3. **Android System Intents & Text Selection Actions** (`ACTION_PROCESS_TEXT`, `ACTION_SEND`, and custom brain action intents) so users and third-party apps can highlight text anywhere on Android or launch an overlay sheet to summarize, extract JSON, rewrite, or save to the shared brain.
- **Confirmed Decision 2 — Shared AI Brain Capabilities**: Prioritize a **Cross-App Memory & RAG Context Graph** (allowing apps to contribute and query semantic context), **Structured JSON Extraction & Intent Routing** (returning schema-validated JSON payloads and actionable intent classifications), and **Per-App Custom System Prompts & Personas** (tailoring tone and domain instructions automatically per calling package).
- **Confirmed Decision 3 — Granular Per-App Security & Live Audit Log**: Each connected app has fine-grained capability toggles (*Inference*, *Read Shared Memory*, *Write Shared Memory*, *Structured JSON / Intent Routing*), configurable rate limits, and a real-time **Live IPC Audit & Telemetry Inspector** showing every cross-app request, latency, token throughput, and authorization status.

---

## 1. Overview & Core Concept

- **What It Does**: Better Intelligence acts as a centralized, on-device neural engine and semantic memory hub for the entire Android device. Instead of every Android app bundling its own 100MB–1GB LLM and maintaining isolated context, client apps delegate AI tasks to Better Intelligence.
- **Target Audience / Persona**:
  - **Android Users** who want unified, private, offline-capable AI across their notes, email, calendar, messaging, and productivity apps with transparent control over which apps can read or write their personal context.
  - **Android Developers** who want a drop-in local AI backend for streaming chat, structured JSON entity extraction, intent classification, and RAG memory without managing native C++/GGUF runtimes.
- **Key Value**: Unifies fragmented app intelligence into a single low-memory on-device brain (powered by the bundled SmolLM2 135M base model or larger downloaded GGUF models) while enforcing strict per-app permissions and zero-cloud data leakage.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Brain Hub & Protocol Control Center (`Connect` Tab)**:
     - Displays real-time health of all three active integration endpoints (*AIDL Binder Service*, *Zero-SDK ContentProvider*, and *System Intent / Text Selection Processor*) alongside total requests served, active model status, and memory graph size.
  2. **Granular App Governance & Persona Configuration**:
     - Tapping any connected app card expands an interactive **App Brain Policy & Persona Drawer** where the user can toggle specific scopes (*Allow AI Generation*, *Read Cross-App Memory*, *Write Cross-App Memory*, *Structured JSON & Intent API*), adjust rate limits (`req/min`), and define a **Custom Per-App System Prompt / Persona** (e.g., *"Always reply in concise bullet points for Workspace Notes"*).
  3. **Cross-App Brain Simulator & Protocol Playground**:
     - Lets users and developers test all brain capabilities live as any connected client app—including **Streaming Chat**, **Structured JSON Extraction** (with custom schema/keys), **Intent Routing**, **Memory Write/Recall (RAG)**, and **Zero-SDK ContentProvider Queries**—and immediately inspect the resulting payload and latency.
  4. **Live IPC Audit Log & Request Inspector**:
     - A filterable real-time feed in the Brain Hub showing every incoming cross-app request (`STREAM`, `JSON_EXTRACT`, `INTENT_ROUTE`, `MEMORY_WRITE`, `PROVIDER_QUERY`, `PROCESS_TEXT`), calling package, status badge (`SUCCESS`, `DENIED`, `RATE_LIMITED`), token speed, and expandable prompt/response inspection.
  5. **System-Wide Text Selection & Brain Action Activity**:
     - Highlighting text in any Android app (or sharing text to Better Intelligence) opens a sleek floating **Better Intelligence Brain Sheet** offering 1-tap actions: *Ask Brain*, *Summarize*, *Extract Actionable JSON*, or *Save to Shared Brain Memory*.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: High-precision **Neural Systems Console**—combining crisp technical telemetry, monospace protocol badges, and layered dark/light slate surfaces.
  - *Color Palette & Mood*: Deep Slate dark surfaces (`#0B0F19` / `#111827`) paired with electric Indigo primary accents (`#6366F1` / `#818CF8`), Sky Blue protocol indicators (`#0EA5E9`), Emerald status pulses (`#10B981`), and Rose security denial alerts (`#F43F5E`).
  - *Typography & Hierarchy*: Bold geometric titles paired with high-contrast monospace telemetry (`FontFamily.Monospace`) for package names, URI endpoints, JSON schemas, and latency metrics.
  - *Component Styling & Layout*: Material 3 adaptive cards with 1dp subtle structural borders, interactive capability filter chips, expandable audit rows, and copyable multi-tab SDK integration snippets (*AIDL Binder*, *ContentProvider URI*, and *Intent Action*).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Tri-Protocol Architecture (AIDL + ContentProvider + System Intents)**
  - *Chosen Approach*: Expose all three native Android IPC mechanisms simultaneously backed by a single unified `AiCoreRepository` and security gatekeeper.
  - *Why*: AIDL Binder provides maximum throughput for token-by-token streaming (`ILocalAiStreamCallback`), whereas `ContentProvider` (`ContentResolver.query` / `call`) and `Intent` actions require **zero SDK files or AIDL compilation** in the client app—making integration effortless for any Android developer.
  - *Alternatives Considered*: Localhost HTTP server only—passed over because Android background network sockets consume higher idle battery and lack native kernel-verified `Binder.getCallingUid()` caller identity verification.

- **Decision 2: Scoped Cross-App RAG Memory & Per-App Personas**
  - *Chosen Approach*: Partition memory access into explicit `canReadMemory` and `canWriteMemory` flags per client app, and attach a customizable `customSystemPrompt` per client app policy.
  - *Why*: An email client might need to read shared context and enforce a formal persona without being allowed to overwrite global user memories, while a notes app may both read and write memories.
  - *Alternatives Considered*: Global all-or-nothing whitelist—passed over because users need granular control over how third-party apps interact with their personal memory graph.

- **Decision 3: Deterministic + Neural Structured JSON & Intent Routing**
  - *Chosen Approach*: Add dedicated `extractStructuredJson(text, schemaFields)` and `routeIntent(text, availableActionsJson)` capabilities to the inference engine and IPC interfaces.
  - *Why*: Client apps using an AI brain for automation (e.g., calendar event parsing, task extraction, deep-link routing) require guaranteed valid JSON objects rather than conversational prose.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        EXTERNAL ANDROID CLIENT APPS                        │
│   ┌──────────────────────┐  ┌──────────────────────┐  ┌────────────────┐   │
│   │  AIDL Binder Client  │  │ Zero-SDK App Query   │  │ System Text /  │   │
│   │ (Streaming & RPC)    │  │ (ContentResolver)    │  │ Share Intents  │   │
│   └──────────┬───────────┘  └──────────┬───────────┘  └────────┬───────┘   │
└──────────────┼─────────────────────────┼───────────────────────┼───────────┘
               │ Binder IPC              │ content:// URI        │ Intent
               ▼                         ▼                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                     BETTER INTELLIGENCE — IPC GATEWAY                      │
│   ┌──────────────────────┐  ┌──────────────────────┐  ┌────────────────┐   │
│   │ LocalAiCoreService   │  │ AiBrainContent       │  │ ProcessText &  │   │
│   │ (ILocalAiCoreService)│  │ Provider             │  │ Share Receiver │   │
│   └──────────┬───────────┘  └──────────┬───────────┘  └────────┬───────┘   │
│              └─────────────────────────┼───────────────────────┘           │
│                                        ▼                                   │
│   ┌────────────────────────────────────────────────────────────────────┐   │
│   │        Security, Rate-Limit, Persona & Memory Scope Enforcer       │   │
│   │    (Verifies Binder Calling UID / Package, Scopes & Rate Limits)   │   │
│   └────────────────────────────────────┬───────────────────────────────┘   │
│                                        ▼                                   │
│   ┌────────────────────────────────────────────────────────────────────┐   │
│   │                AiCoreRepository & Shared Brain Engine              │   │
│   │  • Cross-App RAG Context Injector  • Per-App Persona Prompting     │   │
│   │  • Structured JSON Extractor       • Zero-Shot Intent Router       │   │
│   │  • Local GGUF Inference Engine (SmolLM2 135M Base + HF Models)     │   │
│   └──────────────┬─────────────────────────────────────┬───────────────┘   │
│                  ▼                                     ▼                   │
│   ┌───────────────────────────────┐     ┌──────────────────────────────┐   │
│   │  Room Local Database (v7)     │     │   Jetpack Compose Brain UI   │   │
│   │  • ClientAppPolicyEntity      │     │   • Brain Hub & App Policies │   │
│   │  • MemorySnippetEntity (RAG)  │     │   • Multi-Task IPC Simulator │   │
│   │  • IpcLogEntity (Audit Log)   │     │   • Live Audit Log Inspector │   │
│   └───────────────────────────────┘     └──────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State Enhancements

- **`ClientAppPolicyEntity` (Upgraded)**:
  - Adds granular capability flags: `allowInference: Boolean`, `allowReadMemory: Boolean`, `allowWriteMemory: Boolean`, `allowStructuredJson: Boolean`.
  - Adds `customSystemPrompt: String` for per-app persona customization and `allowedMemoryCategories: String` (comma-separated categories or `"*"` for all).
- **`IpcLogEntity` (Audit Trail)**:
  - Tracks `callingPackage`, `callingUid`, `requestType` (`STREAM`, `SYNC`, `JSON_EXTRACT`, `INTENT_ROUTE`, `MEMORY_READ`, `MEMORY_WRITE`, `PROVIDER_QUERY`, `PROCESS_TEXT`), `promptPreview`, `responsePreview`, `latencyMs`, `tokensPerSecond`, and `status` (`SUCCESS`, `DENIED`, `RATE_LIMITED`, `SCOPE_BLOCKED`).
- **Expanded AIDL & ContentProvider Contract**:
  - `ILocalAiCoreService.aidl` and `LocalAiCoreClient.kt` gain:
    - `extractStructuredJson(String text, in String[] targetKeys)`
    - `routeIntent(String userUtterance, in String[] availableRoutes)`
    - `storeSharedMemory(String title, String content, String category)`
    - `querySharedMemoriesJson(String query, int limit)`
  - `AiBrainContentProvider` exposes URI endpoints (`content://com.aistudio.localaicore.provider/generate`, `/extract_json`, `/memories`, `/status`) and `ContentResolver.call()` methods so any app can query the brain with zero SDK dependencies.

### Interactive Component & State Mapping

- **Per-App Policy & Persona Editor**: Toggling any permission chip (`Read Memory`, `Write Memory`, `JSON/Intent API`) or saving a custom system prompt immediately persists to Room via `AiCoreRepository` and governs the very next IPC or Simulator call.
- **Interactive Multi-Task Brain Simulator**: Switching between *Streaming LLM*, *Structured JSON Extraction*, *Intent Router*, *Cross-App Memory Write*, *Summarize*, and *Vector Embeddings* dynamically updates sample payloads, executes through the real permission/persona pipeline, and appends the transaction to the **Live IPC Audit Log**.
- **Live IPC Audit Log Inspector**: Users can filter cross-app requests by status (*All*, *Success*, *Blocked/Denied*), tap any log entry to inspect the full prompt, injected RAG context, and output payload, or clear audit history.
- **Developer Integration Hub**: Interactive tabs in the `Connect` screen let developers switch between **AIDL Binder SDK**, **Zero-SDK ContentProvider**, and **Android Intent / Process Text** integration code with 1-tap copy to clipboard.
