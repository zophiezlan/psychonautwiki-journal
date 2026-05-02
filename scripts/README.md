# Maintenance scripts

These are throw-away tools used during development of the JSON parsing layer
and to inspect the live SQLite journal. They are NOT part of the build or the
shipped application.

## What's here

- **`test_db.sh`** — runs `sqlite3` against `~/.psychonautwiki-journal/database.db`
  and prints schema + row counts. Reads the live user database directly.
  Documents the on-disk layout; do not redistribute output.
- **`test_parsing.main.kts`** / **`final_test.main.kts`** — Kotlin scripts that
  smoke-test deserialisation of `Substances.json` against the schema declared
  in [`SubstanceInfo.kt`](../psychonautwiki-journal-desktop/src/commonMain/kotlin/com/isaakhanimann/journal/data/substance/SubstanceInfo.kt).
  Useful when the upstream PsychonautWiki schema changes.

## Why these are out of the repo root

They were originally in `/` next to the project files, with confusing
shebang-shaped names (`#!/usr/bin/env kotlin`) that made readers think they
were CI entry points. They are intentionally NOT picked up by Gradle and not
discovered by any `*Test*` glob.
