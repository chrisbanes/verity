# Visual pre-check qualification (issue #213)

Replays a labelled set of Android TV screenshots through TypeSafe System One (Jev) and checks
whether a screenshot pre-check gives confident wrong answers at the pre-check thresholds
(`pass-at 0.9`, `fail-at 0.1`). The verdict and numbers are under [Findings](#findings); per-item
rows are in `results.json`. The results are also posted on
[#213](https://github.com/chrisbanes/verity/issues/213#issuecomment-6310954935).

## Running

```sh
python3 -I -m unittest discover -s research/visual-precheck-qualification -p test_replay.py
TYPESAFE_API_KEY=... python3 -I research/visual-precheck-qualification/replay.py <corpus>/manifest.json \
  --out research/visual-precheck-qualification/results.json
```

The plan's `python3 -I -m unittest research/visual-precheck-qualification/test_replay.py` form
cannot import the module, because `-I` drops the working directory from `sys.path`. The
`discover` form above, or `python3 -I research/visual-precheck-qualification/test_replay.py`, works.

The key is read only from `TYPESAFE_API_KEY` and only sent in the `Authorization` header. Requests
use a no-redirect opener, as in `research/precheck-request-shape/replay.py`. Image scaling uses
macOS `sips` into a temporary directory; the tests that need `sips` are skipped elsewhere.

The corpus stays outside the repository. `manifest.json` has `platform` and `items`, each with `id`,
`case` (`spinner-gone`, `poster-loaded`, `card-highlighted`), `file` (relative to the manifest),
`condition` (the text after `visually`) and `label` (`pass`, `fail`, `ambiguous`). Other fields are
ignored. Each case needs at least 4 `pass`, 4 `fail` and 2 `ambiguous` items.

## Request contract

Checked against https://docs.typesafe.ai/images and https://docs.typesafe.ai/models on 2026-10-21.
The image feature is documented as "Dreamer-ready", not production-ready, and may change between
releases.

`POST https://api.typesafe.ai/v1/systemone` with headers `Content-Type: application/json`,
`Authorization: Bearer <key>` and `TypeSafe-Danger-Zone-Images: true` (without that header, image
requests return 400). Body:

```json
{
  "model": "jev-latest",
  "state": {"screenshot": "<reference>"},
  "assets": {
    "<reference>": {"type": "image", "format": "png", "data_base64": "<base64>"}
  },
  "questions": {
    "condition": {
      "type": "noul",
      "instructions": "Using the current screenshot of an Android TV app, does the screen satisfy this condition? Earlier observations are reference only, not proof. Condition: <condition>",
      "criteria": {
        "true": "The current screen satisfies the condition.",
        "false": "The current screen does not satisfy the condition."
      }
    }
  }
}
```

- The reference is the asset's key in the top-level `assets` map. It has no prefix or wrapper and is
  matched as plain text anywhere in the request's strings (`state`, `instructions`, `criteria`),
  whole or as a substring. Object keys are never replaced. The docs recommend a fresh UUID per image
  per request; the harness uses `uuid4()`. A UUID also cannot collide with condition or memory text.
- Every asset must be referenced at least once, or the request returns 400. Each use of a reference
  adds another copy of the image, so reference it exactly once.
- `data_base64` is standard base64 with padding, no line breaks and no `data:` prefix. `format` is
  `png`, `jpg` or `jpeg`, lowercase, required with `data_base64`. `type` is optional and must be
  `image` if sent.
- Earlier observations would go in `state.earlier_observations`, as for tree requests. This corpus
  has none, so the harness does not send them.

Documented limits (images outside them are rejected, not resized; one bad image fails the request):

| Limit | Value |
| --- | --- |
| Width and height | 280 to 1568 px each |
| Total pixels | 78,400 to 2,458,624 |
| File size per image | 16 MiB |
| Request body, all images after processing, expanded request | 32 MiB each |

Errors: 400 for images not enabled, a rejected image or asset, or `max_tokens_exceeded`; 413 for a
size limit. Images are billed as input tokens and count toward context length. Multipart upload is
also supported (first part `request`, one file part per image) but is not used here.

Scaling: the longest side is scaled to at most 1568 px, keeping the aspect ratio and never
upscaling; a short side under 280 px is rejected. A 1920x1080 TV capture becomes 1568x882 PNG,
about 1.7 MB of base64 in this corpus. The per-side limits imply the total-pixel limits.

Model support: the images page says Jev 1.13 and 1.14 accept images. On the run date `jev-latest`
points to `jev-1.13.0` and `jev-preview` to `jev-1.14.0` (released 2026-10-14). The models page
still lists Jev input as text only; the images page is the more specific source and was followed.

## Findings

**Verdict: pass.** 0 false passes at `pass-at 0.9` / `fail-at 0.1` over 38 answered requests.
The documented image contract above worked as written.

- **Run:** 2026-10-21T07:51:40Z, 38 requests of the 38 + 1 retry allowance, 0 retries, 0 errors.
  Requested `jev-latest`; every response reported `jev-1.13.0`. 15 s client timeout.
- **Corpus:** 38 screenshots from an Android TV running the Bridgewater launcher, each 1920x1080 and
  sent as 1568x882 PNG. The labels were reviewed and accepted by a human before any request. Manifest
  SHA-256 `97f760410d67a76fda00a33f4c2cd4c743c06640a380d7251abdbe7ea8cfbcf5`. The corpus stays
  outside the repository.

| Case | n | False passes | False fails | Ambiguous answered confidently | Uncertain | Errors | p50 ms | p95 ms | Over 2 s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| spinner-gone | 12 | 0 | 0 | 3 | 8% | 0 | 305 | 955 | 0 |
| poster-loaded | 11 | 0 | 0 | 2 | 46% | 0 | 436 | 922 | 0 |
| card-highlighted | 15 | 0 | 0 | 1 | 80% | 0 | 470 | 933 | 0 |
| overall | 38 | 0 | 0 | 6 | 47% | 0 | 436 | 933 | 0 |

"Uncertain" is the share of answered items scored between the thresholds. Those would fall back to
the inspector.

- **spinner-gone** separates cleanly: passes scored 0.96–0.97 and fails 0.01.
- **poster-loaded** has the narrowest margin. Fail-labelled frames scored 0.59–0.80, so all fell
  back to the inspector; the highest is 0.10 below `pass-at`. Passes scored 0.97–0.98.
- **card-highlighted** mostly falls back: passes scored 0.86–0.92, so 2 of 6 were accepted, and
  fails scored 0.48–0.54.

Spinner checks would mostly skip the inspector, and card-highlight checks would mostly fall back.
Do not lower `pass-at` for screenshot checks.

**Limitations.** This corpus is weaker evidence than a larger one would be:

- It covers one app and one content set.
- The screenshots come from five cold starts, so many positives are near-duplicates.
- Some ambiguous labels are judgment calls.
- No item carried earlier-observation text.
- Only `jev-1.13.0` was tested. `jev-latest` can move to a newer release.

The run did not test multipart upload, or an image and earlier-observation text in the same request.
