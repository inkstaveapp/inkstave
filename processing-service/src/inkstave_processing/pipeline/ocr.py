"""OCR metadata extraction (`docs/image-pipeline.md` stage 6): proposes
title/subtitle/composer/arranger/lyricist candidates from a cleaned page
image, using Tesseract OCR (via `pytesseract`, ADR-0002's already-settled
engine choice), in two layers:

1. **Explicit text-label matching, checked first** (`_LABEL_PATTERNS`):
   real sheet music very often states a credit outright -- "Composer: John
   Smith", "Music by John Smith", "Arr. Jane Doe", "Lyrics by ...", "Words
   by ..." -- and a recognized label is a far stronger signal than any
   inferred position, so a line matching one of these patterns is
   classified by its label regardless of where it sits on the page, and
   removed from consideration by the positional heuristic below (title
   included -- a large, centered "Composer: ..." line should not become the
   title candidate just because it's large and centered).
2. **Positional layout heuristic, as a fallback** for whatever a label
   didn't already claim: "title is typically the largest text near the
   top-center; composer/arranger typically top-right/top-left in smaller
   text" (`docs/image-pipeline.md`'s own stated rule, unchanged from the
   previous pass).

**Always proposals.** This module never constructs or mutates a `Manifest`
and never decides anything on a user's behalf -- it returns candidates plus
a confidence score per field for something else (the desktop client's
confirmation UI, a later slice) to let a human accept or correct. This is a
hard requirement stated in `docs/image-pipeline.md`, not a style preference.

**Lyricist is classifiable, but only via an explicit label** ("Lyrics by
...", "Words by ...", "Text by ...", "Lyricist"), never via position. This
refines, rather than reverses, the previous pass's reasoning: *position
-only* guessing for lyricist is still excluded -- title (largest, centered)
and composer/arranger (top-right/top-left) each have a real positional
convention to lean on, but a lyricist credit doesn't have one nearly as
standardized across real engravings, so inferring one from position alone
still risks confidently mislabeling unrelated text. An explicit label is a
different, much safer kind of signal -- it isn't a position guess at all,
it's the page stating outright what the text is -- so it's exempt from that
concern and lyricist becomes classifiable specifically (and only) that way.

**Multiple names for the same field are combined, not reduced to one.**
Two co-composers, or a composer credited on two separate lines, both end up
in that field's candidate as one comma-joined string (`", ".join(...)`) --
`OcrResult.candidates`/`inkstave_format.PageOcr.candidates` stay plain
`dict[str, str]` (matching `docs/format-spec.md`'s `manifest.json` fields,
which are single strings, not lists); a structured multi-value field would
be a bigger, separate format-level decision nobody has asked for yet, so
"multiple composers" is handled by formatting them into one good string
value instead. A name naturally written as one OCR'd line ("John Smith &
Jane Doe") is a single candidate already -- see `_LINE_SPLIT_GAP_HEIGHT_MULTIPLE`
in this module for why normal word/"&"-spacing doesn't get mistaken for two
separate credit blocks sharing a row, and `test_ocr.py` for the regression
test proving it.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace

from inkstave_processing.pipeline.types import ImageU8

#: How far down the page (as a fraction of its height) counts as the
#: "title area" this heuristic operates in at all -- `docs/image-pipeline.md`
#: notes OCR is "realistically only useful on the first page(s)," and within
#: a first page, title-block text is conventionally in the upper portion,
#: not scattered across the whole page (which is mostly music notation this
#: pipeline isn't trying to read).
_TITLE_AREA_HEIGHT_FRACTION = 0.5

#: A text line's horizontal center must fall within this fraction of the
#: page's half-width from the true center to count as "centered" at all
#: (used for both the title's own centeredness weighting and to gate
#: proposing a subtitle) -- loose enough to tolerate OCR bounding-box noise,
#: tight enough that a line hugging one edge is correctly *not* centered.
_CENTERED_TOLERANCE_FRACTION = 0.6

#: Confidence multipliers applied on top of Tesseract's own per-line mean
#: word confidence, reflecting how much this module's own positional
#: assumption should be trusted for that field -- title's positional
#: assumption ("the biggest, most centered text is the title") is strong;
#: subtitle and composer/arranger's are real but weaker conventions (not
#: every engraving centers a subtitle, and some put composer top-left
#: instead of top-right), so their candidates are surfaced with visibly
#: lower confidence rather than claiming equal certainty to title's.
_TITLE_CONFIDENCE_WEIGHT = 1.0
_SUBTITLE_CONFIDENCE_WEIGHT = 0.7
_COMPOSER_ARRANGER_CONFIDENCE_WEIGHT = 0.6

#: An explicit text label ("Composer: ...", "Music by ...") is a far more
#: certain signal than any inferred position -- weighted higher than every
#: positional weight above, though still scaled by Tesseract's own per-word
#: confidence, since OCR can still misread the name itself even when the
#: label is unambiguous.
_LABEL_MATCH_CONFIDENCE_WEIGHT = 0.95

#: Optional separator between a matched label and the name that follows it
#: -- a colon, a dash, or just whitespace (all three appear in real
#: engravings: "Composer: X", "Composer - X", "Composer X").
_LABEL_SEPARATOR = r"\s*[:\-]?\s*"

#: Case-insensitive label patterns for the three fields position alone
#: isn't (or, for lyricist, was never) trusted enough to infer on its own.
#: Title/subtitle deliberately have no label pattern here: sheet music
#: doesn't conventionally prefix a title with "Title:", and forcing a
#: pattern for it without real evidence of that convention would be
#: guessing, the same mistake this module exists to avoid making elsewhere.
_LABEL_PATTERNS: dict[str, re.Pattern[str]] = {
    "composer": re.compile(
        r"^\s*(composed\s+by|composer|music\s+by)" + _LABEL_SEPARATOR,
        re.IGNORECASE,
    ),
    "arranger": re.compile(
        r"^\s*(arranged\s+by|arrangement\s+by|arr\.?|arr:)" + _LABEL_SEPARATOR,
        re.IGNORECASE,
    ),
    "lyricist": re.compile(
        r"^\s*(lyrics\s+by|words\s+by|text\s+by|lyricist)" + _LABEL_SEPARATOR,
        re.IGNORECASE,
    ),
}

#: Tesseract's own sentinel in `image_to_data`'s `conf` column for "this
#: isn't real recognized text" (e.g. a whitespace-only region) -- excluded
#: before grouping words into lines, not treated as a very-low-confidence
#: real result.
_TESSERACT_NON_TEXT_CONFIDENCE = 0

#: How large a horizontal gap between two consecutive words on the same
#: Tesseract-reported line counts as "these are actually two separate text
#: blocks that merely landed on the same row" rather than normal word
#: spacing -- expressed as a multiple of the gap-adjacent words' own
#: average height (a font-size-relative measure, so it scales correctly
#: whether the page/DPI is large or small). Composer (top-right) and
#: arranger (top-left) credits routinely sit on the same visual row in real
#: engravings; Tesseract's own line segmentation can merge them into one
#: reported line when nothing else interrupts that row, which would
#: otherwise corrupt both into a single garbled candidate (caught by this
#: module's own tests -- see `test_ocr.py`'s composer/arranger fixture).
_LINE_SPLIT_GAP_HEIGHT_MULTIPLE = 3.0


@dataclass(frozen=True)
class TextLine:
    """One OCR-recognized line of text (Tesseract's own block/paragraph/line
    grouping), with a union bounding box over its words and their mean
    confidence. Pixel coordinates, in the input image's own coordinate
    space."""

    text: str
    x: float
    y: float
    width: float
    height: float
    #: Mean of this line's words' Tesseract confidences, normalized to `[0, 1]`
    #: (Tesseract itself reports `[0, 100]`).
    confidence: float

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2

    @property
    def bottom(self) -> float:
        return self.y + self.height


@dataclass(frozen=True)
class OcrResult:
    """OCR candidates for one page -- see this module's docstring: always a
    proposal, never auto-committed to a `Manifest`. Shaped to drop straight
    into `inkstave_format.PageOcr` (`engine_version`/`candidates`/
    `confidence` match that model's fields exactly)."""

    engine_version: str
    candidates: dict[str, str]
    confidence: dict[str, float]


def _run_tesseract(image: ImageU8) -> tuple[str, list[TextLine]]:
    """The one place this module touches `pytesseract` directly -- isolated
    here, with a precise return type, so the rest of the module (and every
    caller) never sees `pytesseract`'s own unstubbed (no `py.typed` marker)
    API. `docs/coding-standards.md` requires strict typing throughout,
    including Python; this is this module's equivalent of `cv_backend.py`'s
    wrapper role for `cv2`, just a single small function rather than a
    dedicated module, since the untyped surface used here is far smaller
    (two calls, not dozens).
    """
    import pytesseract  # type: ignore[import-untyped]  # no py.typed marker; isolated to this function

    engine_version = str(pytesseract.get_tesseract_version())
    data: dict[str, list[object]] = pytesseract.image_to_data(
        image,
        output_type=pytesseract.Output.DICT,
    )

    grouped: dict[tuple[int, int, int], list[int]] = {}
    word_count = len(data["text"])
    for i in range(word_count):
        text = str(data["text"][i]).strip()
        if not text:
            continue
        if int(str(data["conf"][i])) < _TESSERACT_NON_TEXT_CONFIDENCE:
            continue
        key = (
            int(str(data["block_num"][i])),
            int(str(data["par_num"][i])),
            int(str(data["line_num"][i])),
        )
        grouped.setdefault(key, []).append(i)

    lines: list[TextLine] = []
    for indices in grouped.values():
        for run in _split_by_horizontal_gap(data, indices):
            lines.append(_line_from_word_indices(data, run))
    return engine_version, lines


def _split_by_horizontal_gap(data: dict[str, list[object]], indices: list[int]) -> list[list[int]]:
    """Splits one Tesseract-reported line's word indices into separate runs wherever the
    horizontal gap between consecutive words is large relative to their own height -- see this
    module's `_LINE_SPLIT_GAP_HEIGHT_MULTIPLE` doc for why: a single reported "line" can
    legitimately contain two unrelated text blocks (e.g. composer top-right, arranger top-left,
    same row) that word-order alone can't otherwise distinguish."""
    ordered = sorted(indices, key=lambda i: int(str(data["left"][i])))
    runs: list[list[int]] = [[ordered[0]]]
    for i in ordered[1:]:
        previous = runs[-1][-1]
        previous_right = int(str(data["left"][previous])) + int(str(data["width"][previous]))
        gap = int(str(data["left"][i])) - previous_right
        avg_height = (int(str(data["height"][previous])) + int(str(data["height"][i]))) / 2
        if avg_height > 0 and gap > _LINE_SPLIT_GAP_HEIGHT_MULTIPLE * avg_height:
            runs.append([i])
        else:
            runs[-1].append(i)
    return runs


def _line_from_word_indices(data: dict[str, list[object]], indices: list[int]) -> TextLine:
    """Builds one `TextLine` as the union bounding box (and mean confidence) over `indices`'
    words, in their original left-to-right reading order."""
    ordered = sorted(indices, key=lambda i: int(str(data["left"][i])))
    lefts = [int(str(data["left"][i])) for i in ordered]
    tops = [int(str(data["top"][i])) for i in ordered]
    rights = [int(str(data["left"][i])) + int(str(data["width"][i])) for i in ordered]
    bottoms = [int(str(data["top"][i])) + int(str(data["height"][i])) for i in ordered]
    text = " ".join(str(data["text"][i]).strip() for i in ordered).strip()
    mean_conf = sum(float(str(data["conf"][i])) for i in ordered) / len(ordered)
    x0, y0, x1, y1 = min(lefts), min(tops), max(rights), max(bottoms)
    return TextLine(
        text=text,
        x=float(x0),
        y=float(y0),
        width=float(x1 - x0),
        height=float(y1 - y0),
        confidence=mean_conf / 100.0,
    )


def _centeredness(line: TextLine, page_width: float) -> float:
    """`1.0` if `line` is exactly horizontally centered on a page of `page_width`, decreasing
    linearly to `0.0` at `_CENTERED_TOLERANCE_FRACTION` of the half-width away, clamped at 0
    beyond that -- used both to pick the title (weighted toward centered, large text) and to gate
    proposing a subtitle (only genuinely centered candidates)."""
    center_x = page_width / 2.0
    tolerance = center_x * _CENTERED_TOLERANCE_FRACTION
    if tolerance <= 0:
        return 1.0 if line.center_x == center_x else 0.0
    return max(0.0, 1.0 - abs(line.center_x - center_x) / tolerance)


def _match_label(text: str) -> tuple[str, str] | None:
    """If `text` starts with one of `_LABEL_PATTERNS`' recognized credit labels, returns
    `(field, remainder)` with the label stripped and whitespace-trimmed; `None` if no pattern
    matches, or if a pattern matches but leaves nothing usable after it (a bare "Composer:" with
    no name isn't a candidate)."""
    for field, pattern in _LABEL_PATTERNS.items():
        match = pattern.match(text)
        if match is None:
            continue
        remainder = text[match.end() :].strip()
        if remainder:
            return field, remainder
    return None


def _classify_by_label(lines: list[TextLine]) -> tuple[dict[str, list[TextLine]], list[TextLine]]:
    """Splits `lines` into label-matched lines, grouped by field (label stripped from each
    line's `text`, via `dataclasses.replace` since `TextLine` is frozen), and the lines with no
    matching label at all -- the pool the positional heuristic falls back to for whatever a label
    didn't already claim."""
    labeled: dict[str, list[TextLine]] = {field: [] for field in _LABEL_PATTERNS}
    unlabeled: list[TextLine] = []
    for line in lines:
        matched = _match_label(line.text)
        if matched is None:
            unlabeled.append(line)
            continue
        field, remainder = matched
        labeled[field].append(replace(line, text=remainder))
    return labeled, unlabeled


def _combined_confidence(lines: list[TextLine], weight: float) -> float:
    """The mean of `lines`' own OCR confidences, scaled by `weight` (how much this module's own
    classification signal -- a label match, or a positional inference -- should itself be
    trusted) and rounded to 3 decimal places, matching this module's existing confidence
    precision elsewhere."""
    return round(sum(line.confidence for line in lines) / len(lines) * weight, 3)


def extract_metadata_candidates(image: ImageU8) -> OcrResult:
    """Runs OCR on `image` and classifies recognized text lines into title/subtitle/composer/
    arranger/lyricist candidates: explicit text labels first, the positional layout heuristic as
    a fallback for whatever a label didn't already claim (see module docstring for the full
    reasoning). Multiple lines classified into the same field -- multiple label matches, or
    multiple lines on the same side of the page -- are combined into one comma-joined candidate
    rather than one replacing the others. Returns empty `candidates`/`confidence` dicts -- not an
    error -- if no text at all is recognized in the page's upper region (a blank page, a page
    with no visible title block, or OCR simply finding nothing): that's a real, expected outcome
    this pipeline's caller (the eventual client-side confirmation UI) is expected to handle as
    "nothing to suggest," not a failure.
    """
    engine_version, lines = _run_tesseract(image)
    if not lines:
        return OcrResult(engine_version=engine_version, candidates={}, confidence={})

    page_height, page_width = image.shape[0], image.shape[1]
    upper_lines = [line for line in lines if line.y < page_height * _TITLE_AREA_HEIGHT_FRACTION]
    if not upper_lines:
        return OcrResult(engine_version=engine_version, candidates={}, confidence={})

    candidates: dict[str, str] = {}
    confidence: dict[str, float] = {}

    # Explicit labels first, and removed from the pool the positional heuristic below ever sees
    # -- a large, centered "Composer: John Smith" line must not also become the title candidate
    # just because it happens to be large and centered.
    labeled, unlabeled_lines = _classify_by_label(upper_lines)
    for field, matched_lines in labeled.items():
        if not matched_lines:
            continue
        candidates[field] = ", ".join(line.text for line in matched_lines)
        confidence[field] = _combined_confidence(matched_lines, _LABEL_MATCH_CONFIDENCE_WEIGHT)

    if not unlabeled_lines:
        return OcrResult(
            engine_version=engine_version,
            candidates=candidates,
            confidence=confidence,
        )

    # Title: the largest remaining (unlabeled) text, weighted toward being centered too --
    # "largest text near the top-center" (docs/image-pipeline.md), stated directly.
    def title_score(line: TextLine) -> float:
        return line.height * (0.5 + 0.5 * _centeredness(line, float(page_width)))

    title_line = max(unlabeled_lines, key=title_score)
    candidates["title"] = title_line.text
    title_centeredness = _centeredness(title_line, float(page_width))
    title_confidence = title_line.confidence * title_centeredness * _TITLE_CONFIDENCE_WEIGHT
    confidence["title"] = round(title_confidence, 3)
    remaining = [line for line in unlabeled_lines if line is not title_line]

    # Subtitle: this module's own extension beyond docs/image-pipeline.md's literal text (which
    # doesn't specify a subtitle position) -- standard engraving convention centers a subtitle
    # below the title, smaller. Lower confidence weight than title reflects that this convention
    # isn't the doc's own stated rule.
    subtitle_candidates = [
        line
        for line in remaining
        if line.y >= title_line.bottom and _centeredness(line, float(page_width)) > 0.5
    ]
    if subtitle_candidates:
        subtitle_line = max(subtitle_candidates, key=lambda line: line.height)
        candidates["subtitle"] = subtitle_line.text
        confidence["subtitle"] = round(subtitle_line.confidence * _SUBTITLE_CONFIDENCE_WEIGHT, 3)
        remaining = [line for line in remaining if line is not subtitle_line]

    # Composer / arranger fallback: "typically top-right/top-left in smaller text"
    # (docs/image-pipeline.md) -- of what's left, *every* remaining line genuinely on the right
    # becomes a composer candidate (not just the single rightmost), same for arranger on the
    # left, combined per this function's own multi-name-combining rule. Skipped entirely for a
    # field a label already claimed above -- a label is a stronger signal than position, so
    # position must not add to or override it.
    center_x = page_width / 2.0
    if not labeled["composer"] and remaining:
        right_lines = sorted(
            (line for line in remaining if line.center_x > center_x),
            key=lambda line: line.y,
        )
        if right_lines:
            candidates["composer"] = ", ".join(line.text for line in right_lines)
            confidence["composer"] = _combined_confidence(
                right_lines,
                _COMPOSER_ARRANGER_CONFIDENCE_WEIGHT,
            )
            claimed = {id(line) for line in right_lines}
            remaining = [line for line in remaining if id(line) not in claimed]
    if not labeled["arranger"] and remaining:
        left_lines = sorted(
            (line for line in remaining if line.center_x < center_x),
            key=lambda line: line.y,
        )
        if left_lines:
            candidates["arranger"] = ", ".join(line.text for line in left_lines)
            confidence["arranger"] = _combined_confidence(
                left_lines,
                _COMPOSER_ARRANGER_CONFIDENCE_WEIGHT,
            )

    return OcrResult(engine_version=engine_version, candidates=candidates, confidence=confidence)
