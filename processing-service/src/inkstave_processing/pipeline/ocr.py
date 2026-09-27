"""OCR metadata extraction (`docs/image-pipeline.md` stage 6): proposes
title/subtitle/composer/arranger/lyricist candidates from a cleaned page
using Tesseract, in two layers:

1. **Explicit labels first** ("Composer: X", "Music by X", "Arr. X",
   "Lyrics by X"). A label beats any position, so a labelled line is
   classified by its label and removed from the positional pool (a large,
   centred "Composer: ..." line must not become the title).
2. **Position as a fallback**: the title is the largest text near the top
   centre; composer/arranger sit top-right/top-left in smaller text.

Results are always proposals with a confidence per field, for a human to
accept or correct; this module never writes a `Manifest`.

Lyricist is only ever taken from an explicit label, never from position:
unlike title and composer/arranger, lyricist credits have no reliable
positional convention.

Several names for one field (co-composers, or a credit split over two
lines) are joined into one comma-separated string, because the manifest's
fields are single strings.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace

from inkstave_processing.pipeline.types import ImageU8

#: Fraction of the page height, from the top, treated as the title area. The
#: rest of the page is mostly notation, which this pipeline doesn't read.
_TITLE_AREA_HEIGHT_FRACTION = 0.5

#: A line counts as centred when its centre is within this fraction of the
#: half-width from the page centre: loose enough for OCR box noise, tight
#: enough that a line hugging one edge isn't centred.
_CENTERED_TOLERANCE_FRACTION = 0.6

#: Multipliers on Tesseract's confidence, reflecting how reliable each
#: positional convention is: "largest centred text is the title" is strong;
#: subtitle and composer/arranger placement vary between engravings.
_TITLE_CONFIDENCE_WEIGHT = 1.0
_SUBTITLE_CONFIDENCE_WEIGHT = 0.7
_COMPOSER_ARRANGER_CONFIDENCE_WEIGHT = 0.6

#: A label is a stronger signal than any position, but it is still scaled by
#: Tesseract's confidence because the name itself can be misread.
_LABEL_MATCH_CONFIDENCE_WEIGHT = 0.95

#: Separator after a label: colon, dash, or whitespace ("Composer: X",
#: "Composer - X", "Composer X").
_LABEL_SEPARATOR = r"\s*[:\-]?\s*"

#: Case-insensitive credit labels. Title and subtitle have none: sheet music
#: doesn't prefix a title with "Title:".
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

#: Tesseract reports confidences below this for regions that aren't real
#: text (e.g. whitespace); those are dropped rather than treated as weak text.
_TESSERACT_NON_TEXT_CONFIDENCE = 0

#: A gap between consecutive words wider than this multiple of their height
#: splits one Tesseract line into two text blocks. Composer (right) and
#: arranger (left) often share a row, and Tesseract can merge them into one
#: line; measuring relative to text height keeps this independent of DPI.
_LINE_SPLIT_GAP_HEIGHT_MULTIPLE = 3.0


@dataclass(frozen=True)
class TextLine:
    """One recognised line of text: the union box of its words (in image pixels)
    and their mean confidence."""

    text: str
    x: float
    y: float
    width: float
    height: float
    #: Mean word confidence, normalised to `[0, 1]` (Tesseract reports 0-100).
    confidence: float

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2

    @property
    def bottom(self) -> float:
        return self.y + self.height


@dataclass(frozen=True)
class OcrResult:
    """OCR candidates for one page, always a proposal. Fields match
    `inkstave_format.PageOcr`."""

    engine_version: str
    candidates: dict[str, str]
    confidence: dict[str, float]


def _run_tesseract(image: ImageU8) -> tuple[str, list[TextLine]]:
    """Runs Tesseract and returns its version and the recognised lines. The only
    place `pytesseract` (which is untyped) is touched, so the rest of the
    module stays strictly typed.
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
    """Splits one Tesseract line's words into separate runs at wide gaps (see
    `_LINE_SPLIT_GAP_HEIGHT_MULTIPLE`)."""
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
    """Builds a `TextLine` from `indices`' words, in left-to-right order."""
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
    """`1.0` for a line centred on the page, falling linearly to `0.0` at
    `_CENTERED_TOLERANCE_FRACTION` of the half-width away."""
    center_x = page_width / 2.0
    tolerance = center_x * _CENTERED_TOLERANCE_FRACTION
    if tolerance <= 0:
        return 1.0 if line.center_x == center_x else 0.0
    return max(0.0, 1.0 - abs(line.center_x - center_x) / tolerance)


def _match_label(text: str) -> tuple[str, str] | None:
    """`(field, name)` if `text` starts with a credit label followed by a name, else
    `None` (a bare "Composer:" with no name isn't a candidate)."""
    for field, pattern in _LABEL_PATTERNS.items():
        match = pattern.match(text)
        if match is None:
            continue
        remainder = text[match.end() :].strip()
        if remainder:
            return field, remainder
    return None


def _classify_by_label(lines: list[TextLine]) -> tuple[dict[str, list[TextLine]], list[TextLine]]:
    """Splits `lines` into label-matched lines per field (label stripped) and the
    unlabelled rest, which the positional heuristic works on."""
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
    """Mean OCR confidence of `lines`, scaled by how much the classification
    (label or position) is trusted, rounded to 3 decimals."""
    return round(sum(line.confidence for line in lines) / len(lines) * weight, 3)


def extract_metadata_candidates(image: ImageU8) -> OcrResult:
    """Proposes metadata candidates for `image`: labels first, then position for
    whatever a label didn't claim. Several lines for one field are joined with
    commas. Returns empty candidates, not an error, when no text is found in the
    title area.
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

    # Labelled lines are removed from the positional pool, so a large centred
    # "Composer: X" line can't also become the title.
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

    # Title: the largest remaining text, weighted toward centred.
    def title_score(line: TextLine) -> float:
        return line.height * (0.5 + 0.5 * _centeredness(line, float(page_width)))

    title_line = max(unlabeled_lines, key=title_score)
    candidates["title"] = title_line.text
    title_centeredness = _centeredness(title_line, float(page_width))
    title_confidence = title_line.confidence * title_centeredness * _TITLE_CONFIDENCE_WEIGHT
    confidence["title"] = round(title_confidence, 3)
    remaining = [line for line in unlabeled_lines if line is not title_line]

    # Subtitle: engraving convention centres it below the title, smaller. A
    # weaker convention than the title's, hence the lower weight.
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

    # Composer/arranger: every remaining line on the right is a composer, on the
    # left an arranger. Skipped for a field a label already claimed, since
    # position must not add to or override a label.
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
