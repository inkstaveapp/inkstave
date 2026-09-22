"""Entry point: runs the service bound to loopback only.

Per ``docs/image-pipeline.md``: "The service exposes a local API
(loopback-only; never bound to a network-reachable interface)". Binding to
``127.0.0.1`` rather than ``0.0.0.0`` is a security requirement, not a
default to change casually -- see ``.claude/agents/image-pipeline.md``.
"""

from __future__ import annotations

import uvicorn

from inkstave_processing.app import create_app

HOST = "127.0.0.1"
PORT = 8787


def main() -> None:
    """Runs the service. See `processing-service/README.md` for how to invoke this."""
    uvicorn.run(create_app(), host=HOST, port=PORT)


if __name__ == "__main__":
    main()
