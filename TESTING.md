# DiaryKeeper testing

DiaryKeeper uses JUnit 5 with Mockito and MockBukkit where Bukkit behavior is required. The normal local verification command is:

```bash
mvn test
```

For a clean build with the same test suite:

```bash
mvn clean test
```

Surefire XML and text reports are written under `target/surefire-reports/`. The repository's Sentinel workflow is separate evidence for exact-head plugin artifact production; it does not replace the unit/integration tests described here.

## What the automated suite currently protects

The suite has direct regression evidence for:

- player identity resolution, aliases, historical names, and Floodgate metadata;
- diary delivery lifecycle behavior, including full-inventory handling and threading boundaries;
- durable `DiaryStore` release/persistence behavior;
- purge planning/execution/recovery and individual purge-chunk retry/terminal state transitions;
- administrative recovery operations;
- ASCII/book-text validation boundaries;
- command-level identity and delivery behavior.

`FullFeatureCoverageContractTest` is an inventory guard. It deliberately fails if one of those established regression suites is removed or moved without updating the inventory. It is not a replacement for behavioral assertions.

## Important remaining coverage gaps

The repository still has significant production surfaces that need additional automated coverage before anyone should call the suite exhaustive:

- `DiaryItem` and `WelcomeBookItem` metadata/rendering behavior;
- the inventory/container/anvil/grindstone/shulker/ender-chest protection listeners;
- the large `RestoreGuiListener` restore workflow;
- `DuplicateWatcher`, `VoidWatcher`, `RestrictionService`, and tracker edge cases;
- analytics persistence plus Plan extension output;
- plugin enable/disable wiring across optional integrations.

Prefer deterministic unit tests for pure state/policy code. Use MockBukkit only where Paper/Bukkit behavior is actually part of the contract. Do not replace missing plugin integrations with mocks merely to claim compatibility; optional integration boundaries should remain explicit.

## Adding a feature or fixing a bug

When behavior changes:

1. add or update a behavioral regression test that fails without the intended change;
2. run `mvn test` and inspect any Surefire failure, not just the final Maven exit code;
3. if the change creates a new major feature family, add its concrete test path to `FullFeatureCoverageContractTest`;
4. keep production data, live server state, and credentials out of tests;
5. keep Sentinel artifact/production compatibility work separate from application behavior tests unless the task explicitly owns both.
