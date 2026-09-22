# Architecture overview

## Components

```
┌─────────────────────────────┐       ┌─────────────────────────────┐
│   Android app                │       │   Linux desktop app          │
│   (Kotlin Multiplatform +    │  LAN  │   (Kotlin Multiplatform +    │
│    Compose Multiplatform)    │◄─────►│    Compose Multiplatform)    │
│                               │ sync  │                               │
│  - Library / viewer / anno-  │       │  - Library / viewer / anno-  │
│    tation UI                 │       │    tation UI                 │
│  - Camera capture             │       │  - Talks to the local        │
│  - Pedal input (BT/USB HID)  │       │    processing service         │
└───────────────┬──────────────┘       └───────────────┬──────────────┘
                │ captured photos                        │ local IPC (HTTP/
                │ (capture session)                       │ WebSocket, loopback)
                ▼                                          ▼
                                                ┌─────────────────────────────┐
                                                │  Processing service (Python)  │
                                                │  - Perspective correct / crop │
                                                │  - Dewarp / flatten           │
                                                │  - Contrast / B&W cleanup     │
                                                │  - Aspect-ratio normalize     │
                                                │  - OCR metadata extraction    │
                                                └─────────────────────────────┘
```

Both apps share one codebase (`client/shared`) for business logic, the
`.smpk` format reader/writer, sync client, and as much UI as practical via
Compose Multiplatform. Platform-specific code (camera access, pedal HID,
packaging) lives in `client/androidApp` and `client/desktopApp`.

The **processing service** only needs to run where the heavy lifting
happens — practically, the Linux desktop (or in principle any machine with
Python + the CV stack installed). It is not expected to run on Android.
It's invoked by the desktop app over a local API (loopback HTTP/WebSocket,
not exposed on the network) — see `docs/image-pipeline.md` for what it does
and `format/` for the schemas both sides agree on.

## Why a separate process for the pipeline, not a library call

Keeping OpenCV/Tesseract/scikit-image in a separate Python process instead of
binding them into the JVM:

- Lets the CV/OCR stack use its native, best-maintained ecosystem
  (ADR-0002) without fighting JVM/native interop.
- Isolates a heavier, slower-to-iterate dependency surface from the client
  app's build and release cadence.
- Keeps the option open to later run the same service on a different host
  (e.g. a home server) without changing the client.

The cost is an IPC boundary — defined precisely so it doesn't become an
undocumented, load-bearing implicit contract. See `format/README.md`.

## Data flow: capture → processed score

1. User starts a **capture session** on the Android app (naming/attaching it
   to a new or existing score).
2. Each photo taken is streamed to the paired desktop over the LAN sync
   transport as it's captured (see `docs/sync-protocol.md`).
3. The desktop app hands each incoming photo to the processing service.
4. The service returns, per photo: a cleaned page image, the detected crop/
   dewarp parameters (kept for reproducibility if reprocessing is needed
   later), and OCR-extracted metadata candidates.
5. The desktop app assembles the results into a draft `.smpk`, and prompts
   the user to confirm/edit metadata (title, composer, etc.) and page order
   before committing it to the library.
6. The finished `.smpk` syncs back to the originating phone (and any other
   paired device) via the same sync transport.

## Repository layout

See the table in the root [README.md](../README.md). Module boundaries
mirror the component diagram above: `client/shared`, `client/androidApp`,
`client/desktopApp`, `processing-service/`, `format/`.

## Cross-cutting concerns

- **Format compatibility** is the contract between the client and the
  processing service, and across versions of the app itself. It's versioned
  explicitly — see `docs/format-spec.md`.
- **Trust/security** for LAN sync (pairing, not silently accepting
  connections from anything on the network) — see `docs/sync-protocol.md`.
- **Licensing** of every dependency pulled into either language's build —
  see `NOTICE.md` and `.claude/agents/licensing-compliance.md`.
