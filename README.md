# DiaryKeeper

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/04437fbeb82c4b3b95dbfb04ff31629c)](https://app.codacy.com/gh/wsg138/DiaryKeeper/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Owner-editable collectible diary plugin with persistent delivery, multi-location tracking,
duplicate detection, and restart-safe administrative purge/restore operations.

## Administrative recovery

All commands use `diary.admin` for backward compatibility.

```text
/diary restore <player|diaryId>
/diary restore <player|diaryId> owner
/diary restore <player|diaryId> admin
/diary restore <player|diaryId> duplicate
/diary purge <player|diaryId>
/diary purge status <operationId|player|diaryId>
/diary purge cancel <operationId>
/diary purge resume <operationId>
/diary purge list
/diary scan <duplicates|locations>
/diary repair
```

`restore` without a mode opens a two-stage confirmation GUI for an in-game
administrator. `owner` removes discoverable copies and restores the saved snapshot
to the diary owner. `admin` performs the same purge but delivers the unchanged diary
to the executing administrator; its owner UUID is not rewritten. If that inventory
is full, delivery remains in the persistent queue. Console may use `owner` or
`duplicate`, but not `admin`.

`duplicate` intentionally creates another copy without removing anything. `purge`
removes copies without restoring one. `scan` reports current duplicate observations.
`repair` scans and starts purge-and-owner-restore operations for confirmed
duplicates; it is no longer only a snapshot refresh.

Purge operations scan online inventories, ender chests, nested bundles/shulkers,
loaded block containers, supported item-holding entities, ground items, and queued
deliveries. Known unloaded chunks are loaded one at a time with a temporary plugin
chunk ticket. The plugin never force-loads the whole map.

Offline players receive persistent join-time purge targets. This means an operation
can remain in `WAITING_FOR_OFFLINE_PLAYERS` until every unresolved player logs in.
Use `purge status` to inspect progress. Incomplete operations and their serialized
diary snapshot resume after a restart or plugin reload.

By default, replacement delivery waits until all required targets succeed.
`purge.allow-restore-on-partial-purge` may enable partial restoration, but an
administrator must explicitly run `purge resume <operationId>` after the operation
enters `PARTIAL` to confirm that risk.

## Purge configuration

```yaml
purge:
  max-players-per-tick: 2
  max-chunks-per-tick: 1
  max-block-entities-per-tick: 50
  max-entities-per-tick: 100
  chunk-load-timeout-seconds: 15
  max-chunk-retries: 3
  max-recursion-depth: 4
  allow-restore-on-partial-purge: false
  post-purge-watch-minutes: 60
  auto-remove-reappearing-copies: false
```

Tracking keeps active and historical locations in the existing `diaries.yml`.
Legacy single-location records are imported automatically on load. Completed purge
IDs remain available for auditing and post-purge duplicate alerts.

## Build

```powershell
mvn clean verify
```
