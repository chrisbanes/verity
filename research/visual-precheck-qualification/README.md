# Visual pre-check qualification (issue #213)

Replays a labelled set of Android TV screenshots through TypeSafe System One (Jev) and checks
whether a screenshot pre-check gives confident wrong answers at the pre-check thresholds
(`pass-at 0.9`, `fail-at 0.1`). The verdict and numbers are in `findings.md`; per-item rows are in
`results.json`.

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
