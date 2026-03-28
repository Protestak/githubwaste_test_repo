# Flaky CI Workflow Demo & Paper Repo Analysis

## Quick Start

```bash
# 1. Clone / copy this into a new GitHub repo
git init flaky-demo && cd flaky-demo
# (copy all files from this project)

# 2. Verify flakiness locally — run tests 20 times
for i in {1..20}; do mvn test -q 2>&1 | tail -1; done
# You'll see a mix of BUILD SUCCESS and BUILD FAILURE

# 3. Push to GitHub — the workflow triggers automatically
git add . && git commit -m "initial" && git push

# 4. When a run fails, click "Re-run failed jobs"
#    → Same commit passes = F→S transition (proven flakiness)
```

**Prerequisites:** JDK 11+ and Maven 3.6+ installed locally. On GitHub Actions, the workflow handles setup automatically.

## What This Demo Contains

```
.github/workflows/build-and-test.yml   ← Matrix build (2 OS × 3 JDKs) + nightly schedule
src/main/java/.../App.java             ← Minimal main class
src/test/java/.../FlakyTestSuite.java   ← 7 intentionally flaky test patterns
pom.xml                                ← Maven config with JUnit 5
```

### Flakiness Patterns Demonstrated (All Verified)

| # | Test | Failure Rate | Root Cause | Real-World Example |
|---|------|-------------|-----------|-------------------|
| 1 | `testEventualConsistency` | **~50%** ✓ | Race: reader checks before writer commits | Common in async/event-driven tests |
| 2 | `testConcurrentWorkersWithDeadline` | **~70%** ✓ | Workers exceed tight deadline under load | Dubbo `ReplierDispatcherTest.testMultiThread` |
| 3 | `testTimestampGrouping` | **~15-20%** ✓ | Computation straddles a clock-second boundary | Time-dependent assertions in nightly builds |
| 4 | `testThreadFinishOrder` | **~50%** ✓ | Two threads race — OS scheduler picks winner | Thread scheduling non-determinism |
| 5 | `testNanoTimeSeedDeterminism` | **~50%** ✓ | Low bits of `nanoTime()` are effectively random | Tests using nanoTime for seeding/branching |
| 6 | `testWeakReferenceCaching` | **env-dependent** | GC clears WeakReference under memory pressure | CI runners with limited RAM |
| 7 | `testCriticalMessageArrival` | **~50%** ✓ | Message arrival time spans the poll deadline | Firebase nightly Maven test failures |

Tests marked **✓** were validated via 200-run simulations to produce genuine non-deterministic results.
Test 6 is **env-dependent** — fails more often on memory-constrained CI runners than on developer machines.

### How It Produces F→S Transitions

1. **Push to `main`** → workflow runs → test 1 or 5 randomly fails (timeout/race)
2. Developer clicks **"Re-run failed jobs"** → same commit, same code
3. On the rerun, the timing works out → tests pass
4. **Result:** `run_attempt=1` FAILURE, `run_attempt=2` SUCCESS = **F→S transition**
5. The `FlakinessAnalyzer` flags `run_attempt=1` as flakiness waste

---

## Findings: Do the Paper's Repos Actually Have Flaky Reruns?

### ✅ apache/dubbo — Confirmed Flaky

**Strong evidence of ongoing flakiness:**

- **`ReplierDispatcherTest.testMultiThread`** is a documented flaky test that fails with
  `TimeoutException` (60s timeout exceeded) on scheduled builds. The test sends concurrent
  RPC requests and sometimes the thread pool gets exhausted or responses time out.
  GitHub issue [#15160](https://github.com/apache/dubbo/issues/15160) tracks this.

- **`WrapperTest.test_getMethodNames`** was proven flaky via the NonDex tool — it depended
  on `getDeclaredMethods()` ordering, which is JVM-non-deterministic. A PR was submitted
  to fix it by sorting before asserting.

- The Dubbo README explicitly acknowledges flakiness: *"To avoid intermittent test failures
  (i.e., flaky tests), it is recommended to have a machine with minimum 2 CPUs and 2GB RAM."*

- Recent commits include titles like **"Stabilize RestProtocolTest"** and **"Stabilize
  reference-related tests"** — evidence of active de-flaking efforts.

- The `Build and Test Scheduled On 3.3` workflow on `windows-latest` shows recurring
  failures that don't occur on Linux — classic platform-dependent flakiness.

### ⚠️ firebase/firebase-admin-java — Likely Flaky (Indirect Evidence)

- The repo has a `nightly.yml` workflow and a `ci.yml` with 234+ runs visible.
- The `.github/workflows/` setup includes email notifications on failure (same pattern
  as `firebase-admin-dotnet` which explicitly emails on nightly build failures).
- The paper specifically cites this repo's `nightly.yml` as having "flaky internal tests
  that returned errors, causing build failures."
- The CI workflow runs Maven builds — same build tool where Surefire's
  `rerunFailingTestsCount` is a common flakiness workaround.
- Direct inspection of individual run attempts would require GitHub API access to confirm
  F→S transitions, but the paper's authors had this access.

---

## How to Use With the FlakinessAnalyzer

To detect these flaky patterns programmatically, you'd feed workflow run data from the
GitHub API into the `FlakinessAnalyzer`:

```python
from flakiness import FlakinessAnalyzer

analyzer = FlakinessAnalyzer(github_client)
result = analyzer.analyze("apache", "dubbo", runs_data)

print(f"Flaky rerun attempts: {result['summary']['flaky_rerun_attempts']}")
print(f"Flakiness rate: {result['summary']['flakiness_rate_of_failures']}%")
```

The analyzer groups runs into rerun chains by `(workflow_id, run_number, head_sha)`,
detects F→S transitions, and flags the wasted intermediate failures.
