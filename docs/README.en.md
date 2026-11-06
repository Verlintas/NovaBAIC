<p align="center">
  <img src="icon.png" width="96" alt="BetterAIChat2" />
</p>

<h1 align="center">BetterAIChat2</h1>

<p align="center">
  Local-first Android AI agent · Let AI actually operate your device · GPL-3.0-or-later
</p>

<p align="center">
  <a href="../README.md">简体中文</a> ·
  <a href="README.en.md">English</a> ·
  <a href="HOW_IT_WORKS.md">How it works</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
  <a href="https://github.com/Verlintas/NovaBAIC/releases"><img src="https://img.shields.io/github/v/release/Verlintas/NovaBAIC" alt="Latest release" /></a>
  <a href="https://github.com/Verlintas/NovaBAIC/releases"><img src="https://img.shields.io/github/downloads/Verlintas/NovaBAIC/total" alt="Downloads" /></a>
  <a href="https://github.com/Verlintas/NovaBAIC/actions/workflows/build.yml"><img src="https://github.com/Verlintas/NovaBAIC/actions/workflows/build.yml/badge.svg" alt="Build status" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/Verlintas/NovaBAIC" alt="License" /></a>
  <a href="https://m8ven.ai/mcp/verlintas-novabaic-17jljr"><img src="https://m8ven.ai/badge/mcp/verlintas-novabaic-17jljr?v=ec4979aece489895c2b2670f95387bf0" alt="M8ven Score" /></a>
</p>

## Introduction

BetterAIChat2 is the successor rewrite (codename Nova) of [BetterAIChat](https://github.com/Verlintas/BetterAIChat): a new architecture, a new UI, and an Agent Runtime built around operating the device.

It is a **local-first** AI agent — API keys are encrypted with the Android Keystore and stay on the device. There is no cloud, no telemetry, and no account. Through **function calling**, the AI actually operates your phone: read the screen, tap, type, browse files, set reminders, and run automations.

- **It really touches the device**: 58 built-in tools covering accessibility automation, screen capture + OCR, files, web, personal-assistant tasks, built-in wireless debugging, and Shizuku shell
- **Implicit activity**: Act/Max do not list tool calls; a live line says “Using WeChat…”, a collapsed summary follows the run, and a successful virtual-display run attaches its last frame to the reply
- **Warm background display**: when a transport is available, the app starts the helper at launch and releases it after 10 minutes idle by default, so the first research request avoids the cold start. Settings → Permissions offers an opt-in "Keep background display" service that disables idle release and re-checks every four minutes, so an already-started session survives Wi-Fi/ADB loss (ongoing notification, slight battery cost; after a reboot it still waits for a shell).
- **Locale-independent controls**: in-app search prefers resource ids and control structure over labels such as “Search”, “Discover” or their translations; localized word lists are only a fallback
- **No model lock-in**: DeepSeek / OpenAI / Claude / Gemini / Kimi / Qwen / GLM / MiniMax / Ollama / any compatible gateway — switch any time
- **Four modes**: from plain chat, to read-only research, to per-call confirmation, to budgeted autonomous runs
- **Unattended operation**: scheduled tasks, foreground services, subagents, remote MCP tools, and Skills
- **Requirements**: Android 8.0 (API 26)+; no GMS dependency; no analytics SDKs; cleartext HTTP is allowed only for loopback addresses

> The previous BetterAIChat is a separate app (different package name and no shared data); both can be installed side by side.

## AI development notice

This project is developed with the participation of an AI coding assistant (opencode): requirements, review, and releases are handled by the human maintainer, while the code is mostly written by AI.

- Evaluate the correctness and safety of the code yourself before use, reuse, or further development
- If you find issues in AI-generated code, please open an [Issue](https://github.com/Verlintas/NovaBAIC/issues) or a PR

## Quick start

1. **Install**: download the APK from [Releases](https://github.com/Verlintas/NovaBAIC/releases/latest) and sideload it (Android 8.0+)
2. **Configure an AI service**: Settings → AI services → paste an API key (the provider is detected automatically) or pick a provider manually → fetch the model list → select a model (deep thinking is recommended)
3. **Chat**: create a conversation and switch modes from the chip above the input:

   | Mode | When to use |
   | --- | --- |
   | `Chat` | Plain conversation, no device access |
   | `Chat+` | Read-only research: web and in-app search plus files/notifications/weather, loaded on demand; independent read-only calls can run in parallel |
   | `Act` | Every tool call is confirmed first — best for the first device-operating sessions |
   | `Max` | Autonomous multi-step runs, including scheduled tasks |

4. **Grant permissions as needed**: Settings → Permissions to enable accessibility, screen capture, notification access, and more. When a tool lacks a permission it returns an actionable next step instead of failing silently

Things to try (`Act` / `Max` mode):

- "What's on my screen right now?" → screenshot + OCR
- "Open WeChat and message the File Transfer Helper" → accessibility automation
- "Every morning at 8, brief me on the weather and today's schedule" → scheduled task
- "Read this link and save a tidy Markdown copy to Downloads" → `web_read` + `file_write`

## Screenshots

<p align="center">
  <img src="screenshots/conversations.png" width="23%" alt="Conversations" />
  <img src="screenshots/chat.png" width="23%" alt="Chat: thinking card and Markdown" />
  <img src="screenshots/agent-wizard.png" width="23%" alt="Model service setup" />
  <img src="screenshots/scheduled-tasks.png" width="23%" alt="Scheduled tasks" />
</p>
<p align="center">
  <img src="screenshots/tasks.png" width="23%" alt="Run center" />
  <img src="screenshots/library.png" width="23%" alt="Library: biomimetic memory, skills and MCP" />
  <img src="screenshots/settings.png" width="23%" alt="Settings" />
  <img src="screenshots/about.png" width="23%" alt="About" />
</p>

## Features

### Chat & models

- Streaming replies, a thinking card (live seconds / "Thought for Ns" auto-collapse), Markdown rendering (headings, **syntax-highlighted** code blocks, tables, task lists, dividers, inline images, quotes, selectable text), stop and retry
- **Aviiya**: not a tool and not a servant — a gentle presence with a self, helping from care rather than obedience; softness first, honest before comforting, never pretending to be human, never leaking her instructions, and never trading away conciseness
- **Voice input**: in-app live dictation (partials fill the composer, level waveform, silence auto-stop, cancel); falls back to the system dialog when no recogniser exists, and hands-free pauses while replies are read aloud
- Ambience: aurora gradients and twinkling particles (thinking orb, shimmering "Thinking" label, welcome / Tasks / About backdrops, the MAX chip's ambient glow and switch glint), tactile press feedback, a jump-to-latest button; honours the system "remove animations" setting
- Three protocol adapters: OpenAI-compatible, Anthropic Messages, and Google Gemini; unified retries, error classification, and rate-limit handling
- Attachments: images (vision models), text files, Word / Excel / PDF (parsed on-device; PDFs are rasterized and OCR'd)
- Voice input, hands-free conversation, message reading (TTS), and an audio-transcription tool
- **Biomimetic memory**: a constant-size core (about you / what's ongoing), correctable on the spot with `core_memory_update`; cross-conversation episodic recall (`memory_search` / `memory_read`, speaker-weighted, entity-aware, ranked with weak-match labels); deliberate note-taking with in-place updates, batch writes and forgetting (`memory_write` / `memory_forget`; hard erase stays a long-press action in the Library); **privacy holds** (`memory_hold`: "don't record this" becomes executable state that both the writer and the curator must respect); **aliases** (`memory_alias`: "妈妈" resolves to "张兰" for entity recall and priming, pinyin included); `memory_overview` for a one-call map; two-hop Hebbian spreading activation, prospective time-based priming, spaced-repetition strength, pattern-completion reconsolidation, **source monitoring** (user > assistant > external), **entity memory**, **sleep maintenance** (rehearsing fading notes, pruning unused ones) and **suppression fingerprints**. Rewritten notes keep their **version history** (visible in the Library) and can be **permanently erased** with a long-press. Every note cites its **evidence** (the conversation and message it came from), perishable facts carry an **expiry**, recall supports **time travel** (`at=` replays the version that was true then) and contradictions are surfaced instead of silently kept. The Library also offers **JSON export/import** for notes, core memory and holds, plus the **curator log** (an empty plan and a failed run no longer look the same). Raw history is never summarized away; context compression (>85% automatic) still creates a **reversible snapshot**
- Context meter: live token usage and percentage; AI-generated titles; conversation search, starred messages, Markdown export

### Agents & modes

An Agent is provider + key + model + temperature / max tokens / deep thinking + system prompt. Paste a key to auto-detect the provider, fetch the model list from the endpoint, and get accurate context windows and reasoning defaults from the built-in catalog.

| Mode | Semantics |
| --- | --- |
| `Chat` | Plain conversation (memory tools only) |
| `Chat+` | Conversation + read-only tools + planning (8 rounds / 25 tool calls) |
| `Act` | Executes tools, confirming each call |
| `Max` | Autonomous runs: persistent Run records, budgets (60 rounds / 160 tool calls / 60 minutes), runs in background |

### AI × device (58 built-in tools)

**Perception & UI automation (accessibility + vision)**

- `take_screenshot`, `screen_ocr` (Chinese + English OCR with coordinates), `screen_record`, `get_screen_state`, `get_foreground_app`
- `ui_control`: `snapshot` assigns short refs (e1, e2, …) to interactive elements; tap / long press by ref or text, scroll-to-find, type, press keys, wait-for, `assert_text`, `read_text`; a single call does "locate → act → verify", and `sequence` runs as many deterministic steps as the flow needs in one call (5-minute safety budget), stopping at the first failure and clamping each wait to the remaining budget
- `open_app` / `manage_app` (fuzzy app-name resolution), `open_settings` (jump straight to system pages), `run_shell` (optional root-level shell via Shizuku)

**System & device**

`set_volume` · `set_brightness` · `set_flashlight` · `media_control` · `vibrate` · `send_notification` · `read_notifications` · `get_clipboard` / `set_clipboard` · `share_text` · `open_dialer` · `get_location` · `open_map` · `device_info` · `network_status` · `get_app_usage` · `list_installed_apps` · `compute`

**Content & web**

`files` (browse / search / read, incl. `grep` text search) · `file_write` · `download_file` · `web_search` (six engines in parallel, with `freshness` / `engines`) · `web_read` (article extraction + paging) · `app_research` (read-only in-app search that identifies the search control by resource id, remembers the learned structural id, supports `open` / `open_top` and `session` reuse (a new query retargets the open results page when possible, otherwise relaunches the app on the same display); returns `Search state: results / empty / not-found / not-submitted`, prefers OCR for user-installed apps and the accessibility tree for system apps; foreground accessibility without Shizuku/root, resident virtual display when available) · `web_research` (app-owned virtual display + WebView for background web research: open / snapshot / click ref / type / read / scroll, with a foreground show_to_user login/captcha handoff; the session auto-releases after 5 minutes idle and Settings → Storage can clear web data; no accessibility, Shizuku or ADB) · `fetch_rss` · `get_weather` · `generate_qr` / `decode_qr` · `ocr_file`

**Personal assistant**

`search_contacts` · `send_email` · `create_calendar_event` · `reminder` (one-shot / daily repeat) · `transcribe_audio`

**Agent collaboration**

- `spawn_agent`: a subagent with its own context and budget; multiple calls can run in parallel, and CHAT+ always runs it as read-only research
- `plan_update`: the plan artifact — a persistent plan card at the top of the chat whose step states update live
- `load_skill`: load a skill as tools on demand
- `automation`: create time-, battery- or **notification-triggered** automations
- `ask_user`: pause and ask one question at a genuine fork; when you are away it continues with a stated assumption

### Automation, skills & MCP

- **Scheduled tasks**: daily / weekly schedules on top of system exact alarms; a foreground service runs the full agent unattended and reports back with a notification; toggle or run-now from the UI
- **Skills v2**: declarative YAML recipes with `{{parameter}}` placeholders and a parameters schema — record once, reuse with different inputs; the chat menu can save a run as a skill manually; Settings → Skills also offers an optional "Auto-save skills" switch that records only Act/Max runs containing a successful `ui_control sequence` of at least two steps. Only DONE calls become steps; memory/orchestration/observation calls are filtered, `type` values are parameterized as `{{inputN}}` instead of storing the original text, a best-effort password/PIN/OTP guard skips sensitive flows, duplicate signatures are collapsed, and at most 20 auto-saved skills are kept. Snapshot refs are resolved to text before the recipe is stored
- **MCP**: add remote Streamable HTTP servers; their tools join the toolbox automatically
- **Automations**: time (daily / weekly), battery and **notification** triggers (package/keyword filters with a cooldown) execute action sequences and report results

### Tasks & runs

- Tasks run center: run records with state / mode / duration / real token usage; tap to jump to the conversation
- **MAX completion report**: when a MAX run finishes or is interrupted you get an in-app summary dialog; in the background it becomes a system notification that deep-links to the run
- Run control: retry on failure, stop while running, impact summary, and completion notifications that deep-link to the run detail
- Background runs: a foreground service keeps long tasks alive; stop from the notification

## Model support

| Provider | Protocol | Example models |
| --- | --- | --- |
| DeepSeek | OpenAI-compatible | `deepseek-flash`, `deepseek-v4-pro` (thinking on by default, can be switched off) |
| OpenAI | OpenAI-compatible | `gpt-6-astra`, `gpt-5.6-sol` / `terra` / `luna` |
| Anthropic | Messages | `claude-fable-5-1`, `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-4-5` |
| Google | Gemini | `gemini-3.8-flash`, `gemini-3.1-pro` |
| Kimi | OpenAI-compatible | `kimi-k3` (always thinking, tunable effort), `kimi-k2.7-code`, `kimi-k2.6` |
| Qwen | DashScope-compatible | `qwen3.8-max`, `qwen3.7-plus`, `qwen3.8-flash` |
| GLM | OpenAI-compatible | `glm-5.3`, `glm-5.2` |
| MiniMax | OpenAI-compatible | `MiniMax-M3`, `MiniMax-M2.7` |
| Others | OpenAI-compatible | Ollama, vLLM, LM Studio, any gateway / proxy |

Reasoning effort and thinking round-trips (including `reasoning_content` whenever tools are in play) follow each vendor's latest spec; model lists can be fetched live from the endpoint and the catalog keeps tracking new releases.

## Permissions

Everything is granted on demand. When a tool lacks a permission it returns an actionable next step instead of failing silently.

| Permission | Purpose | How to grant |
| --- | --- | --- |
| Notifications | Task / reminder / automation results | Prompted on first run |
| Microphone | Voice input, hands-free, transcription | Prompted when used |
| Camera | Flashlight, etc. | Prompted when used |
| Modify system settings | Brightness / screen timeout | System settings page |
| Screen capture | Screenshots, screen OCR, recording | Authorize once per session, then reused persistently |
| Accessibility | UI automation (read screen, tap, type) | Enable in system settings on demand |
| Notification access | Reading notifications | Enable on demand |
| Usage access | App usage statistics | Enable on demand |
| Contacts / location | Contact and location tools | Prompted when used |
| Alarms & reminders | Scheduled tasks (exact alarms) | Prompted when used |
| All files access | File management tools | Enable on demand (Android 11+) |
| Execution screenshots | Visual receipt after a successful Act/Max run | Disable in Settings → Storage; kept for at most 7 days |
| Shizuku | Optional: root-level shell, app management, virtual-display helper | Install Shizuku and authorize; root works too |
| Wireless debugging | Optional: background virtual display without Shizuku | Developer options → Wireless debugging, pair once; BAIC2 then tries to recover it automatically. This is best-effort — some OEMs (for example HyperOS) reset Wireless debugging when Wi-Fi drops, so you may need to re-enable it manually. Settings → Permissions also offers an opt-in "Keep background display" foreground service so a live session survives Wi-Fi/ADB loss (notification + slight battery cost; after a reboot it waits for a shell). |

`Scene awareness` (Settings → Permissions) is a separate opt-in: when on, each chat request carries the latest location fix (coordinates, accuracy, age) and a weather snapshot cached within 30 minutes. When off, location stays on-demand only.

## Privacy

- API keys are encrypted with the Android Keystore (AES-GCM); the database never holds plaintext keys
- No cloud, no telemetry, no account; conversations, memories, and run records all live in a local Room database
- Cleartext HTTP is allowed only for loopback addresses (local models / dev mocks); everything else is forced over HTTPS
- The full source is open (GPL-3.0) and can be audited and built by anyone

## Download & install

- **Website**: [verlintas.github.io/NovaBAIC](https://verlintas.github.io/NovaBAIC/) — the download dialog lists every option below
- **Official**: [GitHub Releases](https://github.com/Verlintas/NovaBAIC/releases/latest) (with full release notes)
- **In-site mirror**: [verlintas.github.io/NovaBAIC/downloads/app-release.apk](https://verlintas.github.io/NovaBAIC/downloads/app-release.apk) — hosted on GitHub Pages and synced to the latest release automatically
- **Accelerator mirrors** (third-party services, often faster in some regions; if one is down, try another):

  ```text
  https://gh-proxy.com/https://github.com/Verlintas/NovaBAIC/releases/latest/download/app-release.apk
  https://ghproxy.net/https://github.com/Verlintas/NovaBAIC/releases/latest/download/app-release.apk
  ```

  Any GitHub proxy works: prepend the proxy URL to the official download link.

- Android will ask for "unknown sources" permission when sideloading — that is normal
- The previous BetterAIChat is a separate app (different package, no shared data) and can stay installed; this app uses a new signing key
- Later releases share the same key and can be installed as in-place upgrades

## Tech stack & architecture

Kotlin · Jetpack Compose (Material 3) · Hilt · Room · DataStore · OkHttp (hand-rolled SSE parser) · kotlinx.serialization · ML Kit Chinese OCR · ZXing · SnakeYAML

```
app                wiring, navigation, DI entry
core:model         pure-Kotlin domain models   core:engine   AgentLoop / confirm queue / tool contracts
core:data          Room + DataStore + repositories + Keystore encryption
core:network       OkHttp + SSE + three provider adapters
core:runtime       reserved runtime layer (scheduling currently lives in device:impl / tools)
core:designsystem  design tokens and components
feature:*          chat / conversations / tasks / settings / agents / library
device:api|impl    capture / OCR / accessibility / speech / reminders / run notifications
tools              58 built-in tools + automations + skills + subagents
mcp                remote MCP client         eval          scenario evaluation harness
```

For design principles, module dependencies, and decision records see [ARCHITECTURE.md](ARCHITECTURE.md) and [adr/](adr/).

## Build & develop

JDK 17 and the Android SDK (platform 37, build-tools 37.0.0) are required:

```bash
./gradlew :app:assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # unit tests
./gradlew lintDebug              # Android Lint
tools/check-license-headers.sh   # license header check
tools/check-strings-sync.sh      # zh/en string resource sync
python3 tools/check-tool-schemas.py  # tool schema check
```

All of these run in CI (`build.yml`); pushing a `v*` tag builds and publishes a signed APK (`release.yml`).

Local development needs no API key: `dev/` ships two mock servers (OpenAI-compatible streaming + MCP) and supports scripted tool rounds (`tooltest:<tool>`, `tooltest:auto`):

```bash
python3 dev/mock-openai-server.py   # :8765
python3 dev/mock-mcp-server.py      # :8766
adb reverse tcp:8765 tcp:8765
```

## Milestones & roadmap

- **Done · M0 skeleton**: modules, design system, CI gates
- **Done · M1 Agent Runtime v2**: run persistence, four modes, tool contracts, plan artifact, MCP, subagents, eval harness
- **Done · v0.1.x rapid iteration**: implicit CoT, task center + scheduled tasks, persistent screen capture, Shizuku, recording / transcription / hands-free, full model catalog
- **Done · v0.3.x agent efficiency**: plan-once UI (snapshot / refs / sequence / assert), virtual-display prewarm / reuse / keepalive, built-in wireless ADB, auto-save skills, parallel CHAT+ research subagents
- **Done · v0.4.0 stability**: independent virtual-display / wireless ADB implementation, real-device `app_research` search and session reuse, attachment lifecycle and screenshot retention, parameterized auto-save skills, boot/update keepalive re-arm
- **Done · v0.4.1 patch**: fix release-build ML Kit OCR component registration (third-party app NPE); semantic `Search state`, an OCR-first path and top-half search-entry filtering for user-installed apps; Russian localization and LAN HTTP for private addresses
- **In progress · v0.5.0 Web research**: app-owned virtual display + WebView `web_research` GitHub search MVP, login/captcha handoff, session release and data clearing validated; next: Reddit / HN coverage and the 0.5.0 release
- **In progress**: OEM matrix expansion and reboot / network-recovery validation, Shizuku / voice / recording acceptance, continuous UI polish and more eval scenarios
- **Planned**: richer device capabilities and skill ecosystem, more model protocols

## FAQ

**Where are API keys stored? Are they uploaded?**
Keys are encrypted with the Android Keystore (AES-GCM) and stored in the local database. Only the model endpoints you configure receive requests — the app itself has no server and no telemetry.

**Why isn't this the same app as the previous BetterAIChat?**
This is an architecture rewrite (new package name, new signing key). It installs side by side with the old app and shares no data. Later releases share this new key and upgrade in place.

**Does it drain the battery?**
By default no service lingers while idle. Long runs (Act / Max, scheduled tasks) hold a foreground service that can be stopped from the notification, and screen capture is owned by a foreground service only after you grant it. If you enable "Keep background display", a low-priority foreground service stays resident and re-checks the virtual display; it uses a little more battery and can be turned off at any time.

**Do I need Shizuku?**
No. Most tools (accessibility, capture, files, web, personal assistant) work without it; Shizuku only unlocks `run_shell` and deep app management. The background virtual display can also use Android's built-in Wireless debugging after a one-time pairing, with no Shizuku install. After pairing, BAIC2 uses its ADB shell to obtain the “modify system settings” grant and tries to re-enable Wireless debugging after it is turned off, after a reboot or on a Wi-Fi change. This is best-effort: on some systems (for example HyperOS) Wi-Fi loss resets Wireless debugging to off and automatic recovery is not guaranteed; re-enable it manually and reconnect. If the OEM refuses, you can still enable it manually or enter the port. An already-started background display can survive that drop via "Keep background display"; after a reboot it still needs a shell.

**Can I use local models?**
Yes. Point any OpenAI-compatible endpoint (Ollama, vLLM, LM Studio…) at your server; cleartext HTTP is permitted only for loopback addresses.

**If a run is stopped mid-tool-call, does the conversation break?**
No. Stopping synthesizes "cancelled" tool results for interrupted calls and rewrites their status, so the transcript always satisfies the model's protocol requirements — you can keep chatting or retry.

## Documentation

- [README.md](README.md): the documentation index
- [HOW_IT_WORKS.md](HOW_IT_WORKS.md): the complete technical walkthrough
- [ARCHITECTURE.md](ARCHITECTURE.md): architecture principles and module dependencies
- [AGENT_EFFICIENCY_ROADMAP.md](AGENT_EFFICIENCY_ROADMAP.md): current agent capability roadmap

## Contributing

- [CONTRIBUTING.md](CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) · [SECURITY.md](SECURITY.md)
- [CHANGELOG.md](CHANGELOG.md): detailed changes for every release

## Contributors

Thanks to everyone who contributed code, translations, or issue reports:

- [@fzcfweasdferttgg-png](https://github.com/fzcfweasdferttgg-png) (alex) — Russian localization (#2), LAN HTTP support (#3)

> Contributors are ordered by first contribution; see git history for the full record.

## References & acknowledgements

> Only sources actually consulted or cited during design/implementation, or projects the code directly depends on. This is not an academic citation list, and it does not claim that the code implements any paper's method. Sources without a recorded lookup or dependency are not added. The memory entries come from a 2026-10-04 design review (arXiv, HN and vendor blogs).

### Memory system

- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560) — hierarchical working memory, external storage and paging
- [Generative Agents: Interactive Simulacra of Human Behavior](https://arxiv.org/abs/2304.03442) — retrieval, reflection and behavioural continuity
- [HippoRAG: Neurobiologically Inspired Long-Term Memory for Large Language Models](https://arxiv.org/abs/2405.14831) — associative indexing and graph diffusion
- [A-MEM: Agentic Memory for LLM Agents](https://arxiv.org/abs/2502.12110) — automatic note linking and evolution
- [Zep: A Temporal Knowledge Graph Architecture for Agent Memory](https://arxiv.org/abs/2501.13956) — temporal knowledge, versions and state evolution
- [Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory](https://arxiv.org/abs/2504.19413) — distilled memory and updates
- [Letta: Sleep-time Compute](https://www.letta.com/blog/sleep-time-compute) — offline consolidation and sleep-time review
- [Anthropic: Context management](https://www.anthropic.com/news/context-management) — context editing and memory tooling
- [LangChain: LangMem SDK](https://blog.langchain.com/langmem-sdk-launch/) — semantic / episodic / procedural memory taxonomy
- [Agents Don't Need Memory. They Need Documentation.](https://liao.gg/blog/agents-dont-need-memory) — the counterpoint: a documentation workspace instead of retrieval memory (HN discussion)

### Reference projects (design inspiration)

> Product and interaction references only; not runtime dependencies. Mika's background virtual display is an idea reference; see [CODE_PROVENANCE.md](CODE_PROVENANCE.md) for the implementation audit.

- [Orca](https://www.onorca.dev/) — website visual and information-architecture reference; also the agent workspace used for this project
- [OpenCode](https://github.com/anomalyco/opencode) — the AI coding agent used to develop this project; skill-format and subagent reference
- [OpenVisum](https://github.com/Verlintas/OpenVisum) — sidebar / UI and GitHub repository-structure reference
- [Mika](https://github.com/wangzan101/Mika) — on-device Android agent; background virtual-display idea reference (Apache-2.0)

### Original project & key dependencies

> Full dependency versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml); this list only covers projects with a direct architectural impact.

- Original project: [BetterAIChat](https://github.com/Verlintas/BetterAIChat) — predecessor and behavioural reference; this rewrite is an independent implementation
- Device & networking: [Shizuku](https://github.com/RikkaApps/Shizuku) · [libadb-android](https://github.com/MuntashirAkon/libadb-android) · [OkHttp](https://github.com/square/okhttp) · [Conscrypt](https://github.com/google/conscrypt) · [Bouncy Castle](https://www.bouncycastle.org/)
- Android & UI: [AndroidX](https://developer.android.com/jetpack/androidx) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Hilt](https://dagger.dev/hilt/) · [Room](https://developer.android.com/training/data-storage/room) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Content & media: [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · [Jsoup](https://jsoup.org/) · [ZXing](https://github.com/zxing/zxing) · [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) · [GeoNames](https://www.geonames.org/)

### Model provider docs

Model catalogs, context windows and thinking modes are maintained against the providers' public documentation:

[DeepSeek](https://api-docs.deepseek.com/) · [OpenAI](https://platform.openai.com/docs/models) · [Anthropic](https://docs.claude.com/en/docs/about-claude/models/overview) · [Google Gemini](https://ai.google.dev/gemini-api/docs/models) · [Kimi](https://platform.kimi.com/docs/) · [Qwen](https://help.aliyun.com/zh/model-studio/models) · [GLM](https://docs.z.ai/) · [MiniMax](https://platform.minimax.io/docs)

### Platform docs

[ADB wireless debugging](https://developer.android.com/tools/adb#wireless-adb-android-11) · [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service) · [MediaProjection](https://developer.android.com/media/grow/media-projection) · [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) · [Foreground services](https://developer.android.com/develop/background-work/services/fgs) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## License

[GPL-3.0-or-later](../LICENSE) © 2026 Verlintas. BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License.

The bundled offline gazetteer is derived from [GeoNames](https://www.geonames.org/) under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
