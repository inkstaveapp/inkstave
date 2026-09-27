"""Entry point: runs the service on loopback only.

Binding to ``127.0.0.1`` rather than ``0.0.0.0`` is a security requirement:
the service must never be reachable from the network.
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
