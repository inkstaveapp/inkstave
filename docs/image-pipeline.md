# Image processing pipeline — draft design

**Status: proposal, not yet implemented.** Runs in `processing-service/`
(Python, ADR-0002), invoked by the desktop client per captured photo (or per
imported image) during M4.

## Pipeline stages

Each stage takes and returns an in-memory image plus a small parameter
record that gets persisted into `pages/<page-id>.meta.json`
(`docs/format-spec.md`) for reproducibility — a page can be reprocessed from
its raw capture later if an algorithm improves, without re-capturing.

**Implementation note (as built, M4's first slice,
`processing-service/src/inkstave_processing/pipeline/`):** stages 1–3 below
(detection, perspective correction, dewarping, cropping) are implemented as
one combined operation (`pipeline/geometry.py`), not three chained ones —
see that module's docstring for why chaining a flat perspective transform
before dewarping would actually be wrong (it discards the boundary
curvature dewarping needs before dewarping ever sees it). The dewarping
model itself is honestly scoped to smooth boundary curl/bowing (a page not
lying flat) via a boundary-fitted mesh (a Coons patch) — it does not correct
local creases or folds, which don't show up on the page's own boundary.
Stages 4–5 (contrast, normalization) match this doc as originally written.



1. **Page detection & perspective correction**
   Detect the sheet-music page's quadrilateral boundary against its
   background (contour detection on edges/color difference) and apply a
   perspective transform to square it up. Candidate approach: OpenCV
   `findContours` + `getPerspectiveTransform`.

2. **Dewarping / flattening**
   Correct for page curl (common when a book/binder won't lie flat) using a
   mesh-based or cylindrical-surface dewarping model, not just a flat
   perspective transform. This is the hardest stage; expect iteration.
   Candidate approaches: document dewarping via detected text/staff-line
   curvature, or a general document-flattening model from the open-source
   document-scanning literature. Must remain fully open-source-licensed
   (see `NOTICE.md`) — no closed-source SDKs.

3. **Cropping**
   Trim to the corrected page bounds detected in stage 1, removing
   background/desk/whatever else was in frame.

4. **Contrast / black-and-white cleanup**
   Adaptive thresholding / CLAHE-style local contrast enhancement to turn an
   unevenly lit photo into crisp black notation on a clean white background,
   without blowing out faint pencil annotations the musician may want kept
   legible. This likely needs a tunable "strength" the user can back off
   from full binarization to a grayscale cleanup, for scores with pencil
   markings.

5. **Aspect-ratio / size normalization**
   Normalize the cropped, cleaned page to a standard aspect ratio class
   (A4, US Letter, or "custom" when the source clearly isn't either) while
   preserving all content — pad rather than stretch/distort. Output at a
   resolution tier suitable for the largest expected display (desktop),
   with the client responsible for downscaling for smaller screens rather
   than the pipeline producing multiple fixed sizes.

6. **OCR metadata extraction**
   Run OCR (Tesseract) over the cleaned page — realistically only useful on
   the first page(s) of a piece — and apply layout heuristics (title is
   typically the largest text near the top-center; composer/arranger
   typically top-right/top-left in smaller text; standard sheet-music
   layout conventions) to propose `title`/`subtitle`/`composer`/`arranger`/
   `lyricist` candidates with a confidence score. These are always
   *proposals* — the client must let the user confirm/edit before they're
   committed to `manifest.json`, never auto-commit silently.

   **Implementation note (M4 slice 2 and its follow-up refinement,
   `processing-service/src/inkstave_processing/pipeline/ocr.py`):**
   `subtitle` is classified via this module's own extension of the above
   heuristic (centered, below the title — a real engraving convention this
   doc doesn't spell out explicitly).

   **Explicit text labels are checked first and take priority over
   position.** Real sheet music often states a credit outright ("Composer:
   John Smith", "Music by John Smith", "Arr. Jane Doe", "Lyrics by ...",
   "Words by ..."); a recognized label is a stronger signal than any
   inferred position, so a matching line is classified by its label
   regardless of where it sits on the page, and excluded from the
   positional heuristic entirely (including title selection). `lyricist`
   is classified **only** via an explicit label, never by position alone —
   unlike title/subtitle/composer/arranger, there's no positional
   convention for it standardized enough across real engravings to guess
   from position without real risk of confidently mislabeling unrelated
   text as "lyricist," but an explicit label isn't a position guess at
   all, so it's exempt from that concern.

   **Multiple names for the same field are combined**, not reduced to one:
   two co-composers (each on their own labeled or positionally-matching
   line) end up as one comma-joined candidate rather than one silently
   replacing the other. The `candidates`/`confidence` shape stays a plain
   `dict[str, str]`/`dict[str, float]` either way — this is string
   formatting, not a structured multi-value field.

   See that module's docstring for the full reasoning, including the exact
   label patterns matched.

## Service interface (sketch)

The processing service exposes a local API (loopback-only; never bound to a
network-reachable interface) that the desktop client calls per photo:

```
POST /process-page
  body: raw image bytes + capture context (session id, sequence index)
  response: { cleanedImage, processingParams, ocrCandidates }
```

Exact request/response schema lives in `format/` once M0 scaffolds it,
shared as the contract both the Kotlin client and Python service test
against.

## Reprocessing

Because `pages/<page-id>.raw.jpg` and the processing parameters are kept
(`docs/format-spec.md`), "reprocess this page" is a first-class, cheap
operation — re-run the pipeline (possibly with different manual overrides,
e.g. the user manually adjusts the detected crop quad) without re-capturing.

## Ownership

Owned by the `image-pipeline` agent (`.claude/agents/image-pipeline.md`) for
stages 1–5, and jointly with `score-format` for how OCR results
(stage 6) map onto `manifest.json` fields.
