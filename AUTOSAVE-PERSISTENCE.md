# DiaryKeeper autosave snapshot remediation

## Why it was slow

The Bukkit autosave timer runs on the server thread. Previously, each dirty save
rebuilt YAML and encoded **every** pending-delivery item, tracked diary snapshot,
and purge-operation snapshot with `BukkitObjectOutputStream` on that same thread.
Only writing the completed YAML file was asynchronous. A large batch could
therefore appear as sporadic slow ticks despite the asynchronous disk write.

## New snapshot behavior

- Unchanged items have a cached Base64 encoding, scoped by tracked diary ID,
  **player plus delivery token**, or purge operation ID. Cache values are created
  only by the normal Bukkit codec on the main thread. The persisted YAML keys,
  item encoding, and file paths are **unchanged**.
- Tracked snapshots are privately cloned. Repeated identical observations retain
  their previous snapshot and encoding; actual item changes invalidate the cache.
  Queued deliveries and purge snapshots are privately cloned when created.
  Reusing a delivery token after cancellation or replacing a purge operation
  invalidates the applicable cache entry. Reload/world reset clears all entries.
  Removed items are dropped from the cache during snapshot creation.
- Periodic autosave warms **at most four missing item encodings per tick**, also
  stopping after approximately 1.5ms once an item completes. The one item already
  being encoded cannot be interrupted, so very large items may still cause a
  slow tick. One in-memory work queue is used and it is bounded by current state.
  If new items arrive while warming, the next sweep includes them.
- Explicit `flushDurably()`, blocking shutdown, and administrative writes **do
  not wait for warmup**: they may serialize a cold set synchronously to preserve
  the existing durability contract. Lifecycle metadata changes reuse cached
  encoded item content but remain fully persisted.
- A write can only clear the dirty flag for its exact snapshot version. A queued
  save finishes before the next one is written; a durable retry is dispatched back
  to the Bukkit server thread after an asynchronous completion. An interrupted
  blocking wait does **not** race the still-running writer.
- Checked and unchecked encoding failures leave data marked dirty; the warmup
  task is cancelled so the next autosave can retry. Write errors are logged and
  retryable. Failure to schedule an async writer also clears its busy state.

**Compatibility:** No `diaries.yml` schema change, no new required configuration
and no change to retention settings. Existing `itemBase64` values remain
readable using the original `BukkitObjectInputStream`. A re-encoded legacy item
uses the same codec as the previous release. Do not hand-edit item blobs.

## Benchmark (synthetic, not production TPS)

`DiarySnapshotBenchmarkTest` runs 30 samples per mode with 64 synthetic
serializable pending items, Java 21, and a no-op persistence writer so it measures
snapshot construction rather than filesystem latency. The uncached mode clears
the cache between otherwise identical dirty saves, modeling repeated prior
serialization; the cached mode retains encoded unchanged items.

| Mode | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Uncached | 3.35 ms | 8.03 ms | 9.47 ms |
| Cached | 0.26 ms | 0.37 ms | 0.38 ms |

Measured on the owner's local Windows workstation, October 11, 2026.
These values are reproducible **fixtures**, not guaranteed latency bounds and
not measured improvement on SMP. Cache warmup moves encoding over ticks but
does not eliminate per-item serialization cost. YAML metadata build and lookup
remain synchronous; cache-hit p50/p95/p99 above therefore include them.
Large-state, Bedrock identity, and real-world MSPT must be retested separately.
The prior low TPS during simultaneous Bloom CPU load is a separate open issue.

## Validation and staged rollout

Run `mvn clean verify` under Java 21. Focused tests exercise cache hits,
invalidation, token scoping/reuse, failed encoding, warmup budgeting,
concurrent dirty snapshots and rescheduling of durable retries. Existing
integration tests cover delivery lifecycle/restart, purge, world reset and
administrative recovery. CI and static analysis must be green on the PR SHA
before a release is considered approved.

For a **future scheduled** update (not performed by this change):

1. With the SMP stopped cleanly, back up `plugins/DiaryKeeper/diaries.yml`,
   any adjacent `diaries.yml.tmp`, and the currently installed DiaryKeeper JAR.
   Keep protected copies off-server. Do not delete pending delivery or purge data.
2. Install the reviewed JAR, restart, inspect Bukkit startup and YAML error logs,
   and confirm existing tracked diaries, pending deliveries and purge operations.
   Monitor slow-tick Spark profiles under a comparable workload, not only TPS.
3. If rolling back, stop cleanly and preserve a copy of **the newest** YAML first.
   Do not blindly replace it with an older backup: operations completed since
   that backup could otherwise be duplicated or lost. Reconcile delivery tokens,
   `CLAIMED`/`RELEASE_PENDING` entries and purge operations before restoration.

No live file mutation, plugin disable, server restart, retention adjustment or
SMP deployment is part of this PR.

## Local PMD 6.55.0 analyzer notes (scoped, no exclusions)

This project predates the change and has existing PMD v6 findings in DiaryStore.
No repository-wide suppressions have been added. The following **new**
diagnostics were assessed one by one for this PR:

- `DiaryStore.warmupTick`: `DataflowAnomalyAnalysis` marks the local
  `started` variable as defined-but-unused despite the explicit time
  subtraction inside the bounded loop. This is a false positive.
- `DiaryStore.flushNow`: `NullAssignment` warns about clearing
  `runningSave` after the scheduler rejects an async task. Clearing the
  failed in-flight future is necessary for safe retry, matching the
  existing completion cleanup. No null is persisted or exposed.
- `DiaryAutosaveWarmupTest.thousandsOfColdItemsUseOneBoundedWarmupAndOneConsistentSave`:
  `DataflowAnomalyAnalysis` reports `owner` as defined-but-unused
  although it is passed to both `updateTrackedSnapshot` and `queueDelivery`.
- `SnapshotItemCacheTest.reusesUnchangedItemsAndInvalidatesEachIdentityIndependently`:
  `DataflowAnomalyAnalysis` reports `cache` unused despite its direct
  calls and counter assertions.
- `SnapshotItemCacheTest.failureNeverCachesPartialEncodingAndCleanupAllowsTokenReuse`:
  the same variable-use false positive; `cache` is used in both failure
  and retry assertions.
- `SnapshotItemCacheTest.cacheIsClearedOnReloadAndNullTokensAreNeverMemoized`:
  the same false positive; `cache` is used before and after reset.

These local tool diagnostics do **not** establish a hosted Codacy result.
Every hosted finding must be reviewed individually before merge.
