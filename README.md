# zk-fingerprint-match

1:N fingerprint identification for ZK 10.0, 12.0 and 13.0 fingerprint templates, using [SourceAFIS](https://sourceafis.machinezoo.com/) (Apache-2.0).

You send one live fingerprint image (or one template), and the service answers with the people whose enrolled fingers match it best. The enrolled templates are read from ZKBio CVSecurity's REST API and kept in memory only.

It has two parts:

- **Template decoders.** `io.github.aleeibrahim.zkmatch.ss21` reads the minutiae out of ZK 10.0 ("SS21") and ZK 12.0 ("SR23") templates, and `io.github.aleeibrahim.zkmatch.zk` reads ZK 13.0 templates and tells the versions apart by their magic bytes.
- **A small HTTP service** (`POST /identify`, `POST /reload`, `GET /health`). It converts every enrolled template to a SourceAFIS template when it loads, and matches each probe against all of them.

> **Interoperability notice.** This project reads data that ZK devices and software store, so that it can be used with other software. It is not affiliated with, endorsed by or supported by ZKTeco. The decoders were written from black-box observation of templates and contain no vendor code, binaries or SDK material. ZKTeco, ZKBio and CVSecurity are trademarks of their respective owners.

## How it works

```
 live image (JPEG/PNG) ───► SourceAFIS extractor ──► probe template ─┐
 or a ZK 10/12/13 template ─► decoder ─► minutiae ─► probe ────────────┤
                                                                        ▼
 CVSecurity ─► each person's templates ─► one version per finger ─► decoder ─► SourceAFIS templates (in memory)
                                                                     │
                                         best score per person, top N, match ≥ threshold, margin
```

- **The gallery.** The service lists every person, but keeps only their id (pin). It then fetches each person's fingerprint templates, decodes them, converts them to SourceAFIS templates straight away and drops the raw bytes. A USB enrollment usually stores the same finger as 10.0, 12.0 and 13.0, so the gallery keeps one version per finger: the first one in `TEMPLATE_VERSIONS` (default `10,12,13`) that decodes. A finger whose 10.0 template cannot be decoded is still found through its 12.0 or 13.0 template, and a person enrolled only in 12.0 or 13.0 is found too. Other versions (such as face templates) are counted and skipped.
- **Refresh.** The gallery is refreshed every `REFRESH_MINUTES` minutes, and also on `POST /reload`. A failed refresh keeps the previous gallery.
- **Matching.** The probe is indexed once per worker thread, and that matcher is reused against every enrolled template. The work is split across `MATCH_WORKERS` threads. A person's score is their best finger.

## API

`/identify` and `/reload` need `Authorization: Bearer $MATCHER_TOKEN`. `/health` is open, because it returns only counts.

### `POST /identify`

```json
{ "image": "<base64 JPEG or PNG, a data: URL is fine>", "threshold": 65, "top": 5, "dpi": 500 }
```

Send either `image` or `template` (a base64 ZK 10.0, 12.0 or 13.0 template; the version is recognised from its magic bytes), not both. `threshold`, `top` (1–20, default 5) and `dpi` (default `IMAGE_DPI`) are optional.

```json
{
  "candidates": [ { "id": "1042", "finger": 5, "version": "10", "score": 124.3 }, { "id": "77", "finger": 6, "version": "12", "score": 24.0 } ],
  "match": true,
  "margin": 100.3,
  "threshold": 65.0,
  "gallerySize": 516,
  "elapsedMs": 31
}
```

- `candidates` holds each person's best finger, highest score first. `id` is the source's person id; for CVSecurity that is the pin. `finger` is the source's finger number, and `version` is the template version that finger was enrolled from (13.0 scores lower against live images, see Limits).
- `match` is true when the best score is at least `threshold`.
- `margin` is the best score minus the second best. A small margin means two people scored close, so ask for another press.

| Status | Meaning |
|---|---|
| 400 | Bad JSON, not base64, an image that cannot be decoded, no usable fingerprint in the image, or an unsupported template (the message says why) |
| 401 | Missing or wrong bearer token |
| 413 | Body larger than `MAX_BODY_BYTES` |
| 503 | The gallery has not loaded yet |

### `POST /reload`

Starts a refresh in the background and answers `202 {"status":"reloading"}`.

### `GET /health`

```json
{
  "status": "ok", "source": "cvsecurity", "templates": 516, "persons": 258,
  "lastRefresh": "2026-11-13T16:00:00Z", "lastAttempt": "2026-11-13T16:00:00Z", "lastLoadMs": 21000,
  "lastError": null, "loading": false,
  "versions": { "10": { "decoded": 514, "used": 514 }, "12": { "decoded": 149, "used": 1 }, "13": { "decoded": 154, "used": 2 } },
  "decodeFailures": { "10:NOT_SS21": 2 }, "skippedVersions": { "39.3": 3 },
  "headerVariants": { "280x361": 220, "299x374": 120, "zk13": 154 },
  "threshold": 65.0, "refreshMinutes": 15
}
```

`versions` counts, per template version, how many templates decoded and how many are in the gallery after one version was chosen per finger. `decodeFailures` is keyed `version:REASON`.

`status` is `loading` before the first load, `ok` once a gallery is loaded (`lastError` then shows a failed later refresh), and `error` (HTTP 503) when the first load failed.

## Configuration

| Variable | Default | |
|---|---|---|
| `MATCHER_TOKEN` / `MATCHER_TOKEN_FILE` | required | Bearer token for `/identify` and `/reload`. At least 16 characters. |
| `GALLERY_SOURCE` | `cvsecurity` | `cvsecurity`, or `directory` for trying the service without a server |
| `CVS_BASE_URL` | | e.g. `https://cvsecurity.example:8098`, without `/api` |
| `CVS_TOKEN` / `CVS_TOKEN_FILE` | | CVSecurity API token. It is sent as the `access_token` query parameter, as the API expects, so request URLs are never logged. |
| `CVS_PUBLIC_KEY_PIN` | | `sha256//BASE64` of the server's public key, for a self-signed certificate. Only a server holding that key is trusted, and the host name is not checked. |
| `CVS_TLS_INSECURE` | `false` | Trust any certificate. For testing only; it is ignored when a pin is set. |
| `CVS_CONCURRENCY` | `4` | Parallel template requests while loading |
| `CVS_TIMEOUT_SECONDS` | `20` | |
| `GALLERY_DIR` | | For `directory`: files named `<id>_<finger>.<ext>`, each holding one base64 template: `.ss21` or `.zk10` for 10.0, `.zk12` for 12.0, `.zk13` for 13.0 |
| `TEMPLATE_VERSIONS` | `10,12,13` | Template versions to load, most preferred first. One version is kept per finger. |
| `REFRESH_MINUTES` | `15` | |
| `MATCH_THRESHOLD` | `65` | Default threshold. Requests can override it. |
| `IMAGE_DPI` | `500` | Resolution of live images (the ZK9500 is 500 dpi) |
| `MAX_BODY_BYTES` | `4194304` | |
| `MATCH_WORKERS` | CPU count | Threads per search |
| `PORT` | `8080` | |

To get the public key pin of a server:

```bash
openssl s_client -connect host:port </dev/null 2>/dev/null | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

## Running

```bash
docker build -t zk-fingerprint-match .
docker run --rm -p 8090:8080 --read-only \
  -e MATCHER_TOKEN_FILE=/run/secrets/matcher_token -e CVS_TOKEN_FILE=/run/secrets/cvs_token \
  -e CVS_BASE_URL=https://cvsecurity.example:8098 -e CVS_PUBLIC_KEY_PIN='sha256//…' \
  -v ./secrets:/run/secrets:ro zk-fingerprint-match
```

The image runs as uid 1000 and writes nothing to disk, so a read-only root file system works. It is stateless: after a restart it rebuilds the gallery from the source, so it needs no backup.

## Personal data

- Enrolled templates exist only in memory, as SourceAFIS templates. The raw templates are dropped as soon as they are decoded. Nothing is written to disk.
- From the person list, only the pin is read. The streaming parser skips photos, passwords and every other field without keeping them.
- Live images and probe templates live only for the request. They are never logged; the buffers are zeroed after decoding.
- Logs carry counts, timings and error classes, never images, templates, pins of probes, tokens or URLs.
- Put the service on an internal network, and let only the application that needs it reach `/identify`.

## Template formats

These layouts were worked out from templates of known captures. The same presses were stored as 10.0, 12.0 and 13.0 together with their images, and the minutiae SourceAFIS found in the images were lined up with the bytes. Only the parts needed for matching are understood.

### ZK 10.0 ("SS21") and ZK 12.0 ("SR23")

| Bytes | Content |
|---|---|
| 0–1 | vary |
| 2–5 | `SS21` (10.0) or `SR23` (12.0) |
| 8–9 | total length, big-endian. It equals the template size in most templates, but not all, so it is not checked. |
| 11–17 | constant: `04 08 05 07 09 CE D0` (10.0) or `05 05 05 07 09 CE D0` (12.0). Any other value is reported as an unsupported variant. |
| 18 → end | XOR-masked by absolute position mod 10: residue 0 → `0x04` (10.0) or `0x05` (12.0), residue 1 → the length's low byte (byte 9), residue 3 → `0x01` (12.0 only), residue 8 → `0xC4`, other residues are unmasked |
| 20–21, 22–23 | image width and height, little-endian (after unmasking). Seen: 280×361 (USB ZK9500), about 299×374 and 287×361 (wall readers). |
| 29 | minutiae count N |
| 30 → 30+6N | N records: x u16 LE, y u16 LE, angle u8 (× 360/256°), flags u8 (not used; in 12.0 it looks like a quality from 0 to 100) |
| after | not interpreted |

10.0 and 12.0 templates of the same enrollment carry nearly the same minutiae (within a few pixels), so they match each other and live images equally well.

Minutiae become SourceAFIS minutiae with the angle in radians. All of them are typed as endings, because the flags byte is not understood.

### ZK 13.0

Every 13.0 template seen is 1552 bytes.

| Bytes | Content |
|---|---|
| 0–5 | `6A 95 01 10 10 06` |
| 6–7, 13–14 | vary |
| 8–12 | constant `00 00 0D 00 01`. Any other value is reported as an unsupported variant. |
| 16 → end | XOR-masked with the position: byte *i* is XORed with (*i* − 16) mod 256 |
| 20 → 1172 | up to 48 records of 24 bytes, ended early by a record with x = y = 0 |
| 1172 → end | not interpreted (it looks like an orientation grid and a 2-bit foreground mask) |

A record holds x s16 LE, y s16 LE, an angle u8 in 5° steps (0–71), five bytes that are not used (one looks like a quality, three like a scale level), and a 15-byte local descriptor that is not used. The coordinates are in a frame that is transposed against 10.0 and scaled by 0.8: a record at (x, y) is the 10.0 minutia at about (1.25·y, 1.25·x), plus an offset. Points just outside the image have negative coordinates, so the decoder shifts the whole template to start at 0; SourceAFIS ignores translation. The angle is already in the 10.0 frame.

13.0 points come from a different extractor. About two thirds of them coincide with 10.0 minutiae; the rest do not.

## Limits

- Some ZK 10.0 templates do not use the SS21 layout; on one real server that was 2 of 518. They are counted under `decodeFailures`, and the finger is used through its 12.0 or 13.0 template when it has one.
- **13.0 is the weakest version.** On real data, a 13.0 template scored 213–315 against 13.0 templates of the same finger and 66–191 against 10.0/12.0 ones, but only 17–117 (median about 85) against single live images, while other people scored at most 28. 10.0 and 12.0 templates scored 50–240 against the same images. That is why 13.0 comes last in the default `TEMPLATE_VERSIONS`. A person enrolled only in 13.0 may be ranked first with a score below the threshold, so look at the margin too.
- The decoders are black-box derived. A firmware or software update could change the format; the decode-failure counts in `/health` show when that happens.
- Scores are SourceAFIS scores, not ZK's. In tests on real data (about 260 people), different people scored up to 47 against 10.0 templates (30 against 12.0, 28 against 13.0), and the same finger scored 76–326 (single live presses: 76–170). So the default threshold is 65. Tune it for your own population, and use the margin as well as the threshold.
- A single press gives fewer minutiae than an enrollment. Ask for a firm, flat press.
- This is an identification aid for a person who confirms the result. It is not a liveness or anti-spoofing check.

## Development

```bash
mvn verify            # build and run the tests (synthetic data only)
mvn test -Pbench      # identify latency against a synthetic gallery of 600
```

Without a local Maven:

```bash
docker run --rm -v "$PWD:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B verify
```

The tests use only made-up data. Some write 10.0, 12.0 and 13.0 templates from random minutiae and decode them again. Others draw fingerprint-like ridge images from a phase field with "vortices" where ridges end or split. **Do not add real fingerprint images or templates to this repository**, including your own. `.gitignore` blocks the usual capture file types.

### Benchmark (synthetic gallery of 600 fingers, 20-core desktop)

Each finger is stored as 10.0, 12.0 and 13.0, except one in ten that has only 13.0, so 1680 stored templates become 600 gallery entries.

| Probe | 1 worker p50 | 20 workers p50 / p95 |
|---|---|---|
| 10.0 template | 33 ms | 8 / 16 ms |
| 13.0 template | 33 ms | 8 / 14 ms |
| Image (extract + search) | 58 ms | 27 / 40 ms |
| Image extraction alone | 20 ms | |

Loading decodes and converts a template in under 1 ms. With a real server, loading time is dominated by one API call per person.

## Licence

MIT, see [LICENSE](LICENSE). SourceAFIS is Apache-2.0, and Jackson is Apache-2.0.
