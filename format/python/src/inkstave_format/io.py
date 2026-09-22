"""Convenience JSON (de)serialization for Inkstave format models.

Plain ``model.model_dump_json()`` is not enough on its own: without
``by_alias=True`` it would emit Python's snake_case field names instead of
the format's camelCase JSON keys (see ``models.py``). These helpers wrap
that so callers don't have to remember the flag.
"""

from __future__ import annotations

from pydantic import BaseModel


def parse_json[ModelT: BaseModel](model_type: type[ModelT], text: str) -> ModelT:
    """Parses `text` as JSON into `model_type`, preserving fields it doesn't recognise."""
    return model_type.model_validate_json(text)


def dump_json(model: BaseModel, *, indent: int | None = 2) -> str:
    """Serializes `model` back to JSON text with camelCase keys, including any
    unrecognised fields it was parsed with (see `models.py`'s module docstring)."""
    return model.model_dump_json(by_alias=True, indent=indent)
