# `client/`

Kotlin Multiplatform + Compose Multiplatform application, targeting Android
and Linux desktop (ADR-0001, `../docs/decisions/0001-client-framework.md`).

**Not yet scaffolded** — this is a placeholder for M0
(`../ROADMAP.md`). Planned module layout:

```
client/
├── shared/       Business logic shared by all targets: .smpk format
│                 reader/writer, sync client, annotation model, view models
├── androidApp/   Android-specific: camera capture, storage/SAF, Bluetooth/
│                 USB pedal HID, Android UI entry point
└── desktopApp/   Linux-desktop-specific: packaging (Flatpak/AppImage),
                  processing-service client, pedal input, desktop UI entry
                  point
```

Owned by the `client-ui`, `android-platform`, and `linux-desktop` agents —
see `../.claude/agents/`.
