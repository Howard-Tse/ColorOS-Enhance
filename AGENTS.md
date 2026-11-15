# Module instructions

- If the parent workspace contains `AGENTS.md`, read and follow it. Before Android work, read the shared Android instructions from `CODEX_ANDROID_ROOT/AGENTS.md` when configured, or from `../../codex-android/AGENTS.md` when that local environment exists. A configured but missing environment is an error; do not silently replace it.
- This is an independent Git repository. Keep all edits, tests, reports and generated files here. Source integrations are authorized; do not modify the source modules.
- Public behavior and publication boundaries are described in `README.md`, `docs/PUBLISHING.md` and `docs/PRIVACY.md`. Historical device notes under `docs/` are private and ignored; do not force-add them.
- Do not commit, push or change device state without explicit authorization for that operation.
- Use the checked-in Wrapper and shared environment; run offline first. Target ColorOS 16 / Android 16 and ColorOS 17 / Android 17; preserve original native rendering, authentication and single PIN attempt guarantees. Keep firmware-specific adapters capability-checked and retain the older interface path.
- Credentials, signing material and device captures belong only in ignored module-specific locations. Never print PINs or signing secrets. Never share the old modules' credential storage or keystore aliases.
- Run `scripts/Audit-Publish.ps1` before staging or publishing. It checks candidate Git files without staging them. Author name Howard and the existing package namespaces are deliberately public; device identifiers, private paths, credentials and diagnostic captures are not.
