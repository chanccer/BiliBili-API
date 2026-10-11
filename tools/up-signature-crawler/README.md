# Bili UP Signature Crawler

This standalone Java 17 tool uses [abcLiyew/BiliBili-API](https://github.com/abcLiyew/BiliBili-API).
It discovers public video authors, saves their latest public signatures in SQLite,
and exports an Excel-compatible CSV. It does **not** enumerate every Bilibili account
or guarantee full-site coverage. No follower/consumer lists are collected.

## Build and run

Use a working JDK 17+ and Maven:

```sh
cd /Users/nccer/bilibili/BiliBili-API
mvn install
cd tools/up-signature-crawler
mvn package
java -jar target/bili-up-signature-crawler.jar crawl --target 10
java -jar target/bili-up-signature-crawler.jar crawl --target 1000
```

Alternatively use Docker; this avoids a broken or incompatible host JDK:

```sh
cd /Users/nccer/bilibili/BiliBili-API/tools/up-signature-crawler
bash docker-run.sh build
bash docker-run.sh crawl --target 10
bash docker-run.sh crawl --target 1000
bash docker-run.sh export
```

Docker mounts the repository and, for builds only, the existing Maven cache.
Keep CLI file paths relative to this tool's directory when using Docker.
The wrapper uses an ARM64 image on Apple Silicon. Set `BILI_MAVEN_IMAGE` to use
a preinstalled compatible Maven/JDK image if necessary.
On this machine, the wrapper automatically uses the prepared
`bili-crawler-runtime:local` image, with Corretto 17 and Maven 3.9.14.

## Data and resuming

Default output files, relative to the current directory:

- `data/up-signatures.sqlite`: latest profiles, UID queue, discovery cursors and provenance.
- `data/up-signatures.csv`: UTF-8 with BOM, one row per distinct saved UID.
- `data/up-signatures.sqlite.lock`: exclusive process lock; the file may remain after exit.

Fields: `uid`, `name`, `sign`, `face`, `followers`, `archive_count`, `space_url`,
`sources`, `discovered_at`, `collected_at`. UIDs use 64-bit storage. Signature text
is preserved verbatim, including emoji and line breaks. An empty signature is valid;
a missing signature is an error. Failed requests never replace existing profiles.
SQLite stores only the latest profile, without signature history.

Repeat the same crawl command to resume. `--target` counts **all** saved UIDs,
including previous runs; a 10-author preflight followed by `--target 1000` adds
up to 990 more authors. Successful profiles are not requested again on resume.
Stopping with Ctrl-C/SIGTERM saves completed transactions and exports available rows.
The CSV is replaced atomically after a full successful write. Hard kills preserve
committed SQLite records; use `export` to regenerate the CSV afterward.

```sh
java -jar target/bili-up-signature-crawler.jar export \
  --db data/up-signatures.sqlite --output data/up-signatures.csv
```

Database, CSV, log and credential files are ignored by Git. Do not force-add them.
CSV preserves original cell content; import columns as text when viewing untrusted
signatures in a spreadsheet.

## Discovery configuration

Discovery uses configured partition rankings, popular videos, and keyword video
searches. UIDs are deduplicated across every source; each source is retained as
provenance. Default ranking IDs:
`0,1,3,4,5,36,119,129,155,160,181,188,202,211,217,223,234`.
Unavailable partition IDs are recorded and skipped. Popular videos are limited to
100 pages; each search keyword is limited to 50 pages. Empty, repeated and final
pages terminate a source. Raising a page cap resumes sources that hit the cap.

Default search topics cover animation, music, dance, gaming, technology, digital
devices, programming, knowledge, cars, sports, food, travel, daily life, animals,
fashion and film. Supply a UTF-8 keyword file with one keyword per line;
empty lines and lines beginning with `#` are ignored.

```sh
java -jar target/bili-up-signature-crawler.jar crawl --target 1000 \
  --interval-ms 3000 --ranking-ids 0,1,3,4,36 \
  --keywords-file keywords.txt --popular-pages 150 --search-pages 70
```

Discovery page/UID changes are transactional. Sources removed from the current
configuration remain as provenance but are not requested. New keywords extend
coverage. If sources exhaust before the target, the tool exports the actual result
and returns exit code 2. Coverage favors publicly discoverable authors and is not
an unbiased sample of the platform.

## Requests and errors

Default global request interval: 3000 ms. It also applies to the anonymous device
fingerprint bootstrap and WBI key requests. The tool fixes its User-Agent and
disables the upstream client's automatic retries and identity rotation.

Transient network/server errors have at most three attempts, with 10s and 30s
backoff. HTTP 412/429 and business codes -352/-509/-412 pause the entire crawl
immediately and retain the current task/cursor. Rerun manually later to resume.
Permanent errors and exhausted transient retries are stored as failures;
`--retry-failed` reactivates those tasks and failed sources. Otherwise resume
does not continuously retry known failures.

No login is required by default. An existing valid Cookie file may optionally be
injected with upstream `-Dbili.cookieFile=/path/to/cookie.txt`, before `-jar`.
The tool never reads browser credentials automatically and never logs Cookie values.
Do not use identity/proxy rotation to keep sending requests after a risk response.

Exit codes: `0` target reached or export completed; `2` paused/stopped/incomplete;
`1` local configuration, database, export or unexpected error.

## Tests

`mvn test` inside this directory exercises cross-source UID deduplication, SQLite
transaction rollback, resumability, page exhaustion, latest-only updates, exact CSV
round trips, retry limits, global rate limiting, and immediate stopping on risk.
All automated tests are offline. Live verification is a separate `crawl --target 10`.
