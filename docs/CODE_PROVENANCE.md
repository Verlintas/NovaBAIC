# Code provenance & reference projects

> Last audit: 2026-11-01
>
> Reference for similarity checks: Mika `16a5adc` (v0.1.26, Apache-2.0)

This document records which projects influenced the design, what was actually reused, and how the virtual-display implementation was checked after the Pre-0.4.0 review.

## Reference projects

- **BetterAIChat** — the predecessor project. It is the behavioural reference for the rewrite; this repository is a separate implementation with a new architecture.
- **Mika** — the resident background virtual display is its idea: run a privileged helper that owns a `VirtualDisplay` and keeps it alive between agent calls. The implementation here was rewritten independently; Mika is not a runtime dependency.
- **Orca** — visual and information-architecture reference for the project website, and the agent workspace used during development.
- **OpenCode** — the AI coding agent used to develop the project; its skill format and subagent model were design references.
- **OpenVisum** — sidebar/UI and GitHub repository-structure reference.

## Virtual display & wireless ADB

The implementation follows Mika's *idea*, not its source layout:

- `DisplayHelperMain` is an `app_process` entry point. It uses `ServiceManager → IActivityManager` to obtain an external provider handle because `ContentResolver.call` cannot resolve an app provider from an `app_process` caller (the platform rejects it with "Unable to find app for caller"). This is a platform constraint, not a copied design.
- Provider ownership is checked through package + uid; the handoff uses a one-shot nonce and an app-death Binder token.
- `startApp` uses `am start --display` inside the helper instead of reflecting into `ActivityTaskManager`.
- `readTlsPort` uses `getprop service.adb.tls.port` instead of `SystemProperties` reflection.
- The IME policy uses the `IWindowManager` service first, with a `WindowManagerGlobal` fallback only on OEM builds that hide the service.
- Wireless host discovery is an explicit loop over Wi-Fi `LinkProperties`; port scanning is the independent `LocalPortScan` implementation.

## Audit method

1. Tokenize Kotlin after removing comments, imports and package declarations.
2. Compare 12-token shingles and the longest common token block against the reference tree.
3. Inspect every match above 20 tokens by hand.

After the Pre-0.4.0 rewrite, the longest shared blocks in the virtual-display/ADB files are generic Android API boilerplate: `ContextWrapper` identity overrides, `ContentProvider` CRUD signatures, `ContentObserver`/`NetworkCallback` declarations, foreground-service overrides, `KeyStore` GCM parameter setup, and `ContentResolver` `OpenableColumns` queries. No Mika-specific comments, class names, protocol fields or command strings remain; the remaining shared blocks are generic Android API boilerplate.

The remaining matches in network tests are JSON wire-format fixtures and MockWebServer scaffolding, not application code.

## Third-party code policy

- No third-party source is copied into this repository.
- Runtime dependencies keep their own licences; see `gradle/libs.versions.toml` and the dependency licences.
- If a future change adapts Apache-2.0 code, add the copyright/NOTICE and mark the modified file before merging.
