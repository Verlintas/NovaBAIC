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
  <a href="../CHANGELOG.md">Changelog</a>
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

- **It really touches the device**: 57 built-in tools covering accessibility automation, screen capture + OCR, files, web, personal-assistant tasks, and Shizuku shell
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
   | `Chat+` | Read-only research: notifications, files, web search, weather |
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
- **Biomimetic memory**: a constant-size core (about you / what's ongoing), correctable on the spot with `core_memory_update`; cross-conversation episodic recall (`memory_search` / `memory_read`, speaker-weighted, entity-aware, ranked with weak-match labels); deliberate note-taking with in-place updates, batch writes and forgetting (`memory_write` / `memory_forget`, soft or hard); **privacy holds** (`memory_hold`: "don't record this" becomes executable state that both the writer and the curator must respect); **aliases** (`memory_alias`: "妈妈" resolves to "张兰" for entity recall and priming, pinyin included); `memory_overview` for a one-call map; two-hop Hebbian spreading activation, prospective time-based priming, spaced-repetition strength, pattern-completion reconsolidation, **source monitoring** (user > assistant > external), **entity memory**, **sleep maintenance** (rehearsing fading notes, pruning unused ones) and **suppression fingerprints**. Rewritten notes keep their **version history** (visible in the Library) and can be **permanently erased** with a long-press. Every note cites its **evidence** (the conversation and message it came from), perishable facts carry an **expiry**, recall supports **time travel** (`at=` replays the version that was true then) and contradictions are surfaced instead of silently kept. The Library also offers **JSON export/import** for notes, core memory and holds, plus the **curator log** (an empty plan and a failed run no longer look the same). Raw history is never summarized away; context compression (>85% automatic) still creates a **reversible snapshot**
- Context meter: live token usage and percentage; AI-generated titles; conversation search, starred messages, Markdown export

### Agents & modes

An Agent is provider + key + model + temperature / max tokens / deep thinking + system prompt. Paste a key to auto-detect the provider, fetch the model list from the endpoint, and get accurate context windows and reasoning defaults from the built-in catalog.

| Mode | Semantics |
| --- | --- |
| `Chat` | Plain conversation (memory tools only) |
| `Chat+` | Conversation + read-only tools + planning (8 rounds / 25 tool calls) |
| `Act` | Executes tools, confirming each call |
| `Max` | Autonomous runs: persistent Run records, budgets (60 rounds / 160 tool calls / 60 minutes), runs in background |

### AI × device (57 built-in tools)

**Perception & UI automation (accessibility + vision)**

- `take_screenshot`, `screen_ocr` (Chinese + English OCR with coordinates), `screen_record`, `get_screen_state`, `get_foreground_app`
- `ui_control`: fuzzy find by text / description, then tap / long press / scroll-to-find / type / press keys / wait-for; a single call does "locate → act → verify", using the screen diff before and after as the verification signal
- `open_app` / `manage_app` (fuzzy app-name resolution), `open_settings` (jump straight to system pages), `run_shell` (optional root-level shell via Shizuku)

**System & device**

`set_volume` · `set_brightness` · `set_flashlight` · `media_control` · `vibrate` · `send_notification` · `read_notifications` · `get_clipboard` / `set_clipboard` · `share_text` · `open_dialer` · `get_location` · `open_map` · `device_info` · `network_status` · `get_app_usage` · `list_installed_apps` · `get_time` · `compute`

**Content & web**

`files` (browse / search / read, incl. `grep` text search) · `file_write` · `download_file` · `web_search` (six engines in parallel, with `freshness` / `engines`) · `web_read` (article extraction + paging) · `fetch_rss` · `get_weather` · `generate_qr` / `decode_qr` · `ocr_file`

**Personal assistant**

`search_contacts` · `send_email` · `create_calendar_event` · `reminder` (one-shot / daily repeat) · `transcribe_audio`

**Agent collaboration**

- `spawn_agent`: a subagent with its own context and budget; multiple calls can run in parallel
- `plan_update`: the plan artifact — a persistent plan card at the top of the chat whose step states update live
- `load_skill`: load a skill as tools on demand
- `automation`: create time- or battery-triggered automations

### Automation, skills & MCP

- **Scheduled tasks**: daily / weekly schedules on top of system exact alarms; a foreground service runs the full agent unattended and reports back with a notification; toggle or run-now from the UI
- **Skills v2**: declarative YAML recipes; imported skills load as tools on demand, and a completed run can be "saved as a skill"
- **MCP**: add remote Streamable HTTP servers; their tools join the toolbox automatically
- **Automations**: time (daily / weekly) and battery triggers that execute action sequences and report results

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
| Shizuku | Optional: root-level shell and app management | Install Shizuku and authorize |

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
tools              57 built-in tools + automations + skills + subagents
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
- **In progress**: real-device acceptance (Shizuku / voice / recording), continuous UI polish, more eval scenarios
- **Planned**: richer device capabilities and skill ecosystem, more model protocols

## FAQ

**Where are API keys stored? Are they uploaded?**
Keys are encrypted with the Android Keystore (AES-GCM) and stored in the local database. Only the model endpoints you configure receive requests — the app itself has no server and no telemetry.

**Why isn't this the same app as the previous BetterAIChat?**
This is an architecture rewrite (new package name, new signing key). It installs side by side with the old app and shares no data. Later releases share this new key and upgrade in place.

**Does it drain the battery?**
No services linger while idle. Long runs (Act / Max, scheduled tasks) hold a foreground service that can be stopped from the notification, and screen capture is owned by a foreground service only after you grant it.

**Do I need Shizuku?**
No. Most tools (accessibility, capture, files, web, personal assistant) work without it; Shizuku only unlocks `run_shell` and deep app management.

**Can I use local models?**
Yes. Point any OpenAI-compatible endpoint (Ollama, vLLM, LM Studio…) at your server; cleartext HTTP is permitted only for loopback addresses.

**If a run is stopped mid-tool-call, does the conversation break?**
No. Stopping synthesizes "cancelled" tool results for interrupted calls and rewrites their status, so the transcript always satisfies the model's protocol requirements — you can keep chatting or retry.

## Contributing

- [CONTRIBUTING.md](../CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) · [SECURITY.md](../SECURITY.md)
- [CHANGELOG.md](../CHANGELOG.md): detailed changes for every release
- [HOW_IT_WORKS.md](HOW_IT_WORKS.md): the complete technical walkthrough

## License

[GPL-3.0-or-later](../LICENSE) © 2026 Verlintas. BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License.

The bundled offline gazetteer is derived from [GeoNames](https://www.geonames.org/) under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
