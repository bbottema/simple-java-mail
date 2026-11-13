# Verification

Follow [docs/TESTING.md](docs/TESTING.md) when selecting checks. Start with the tests that exercise the changed behavior and its nearest integration boundary; expand for shared configuration, public APIs, providers, and cross-module lifecycles. Test locations do not always match production-module locations.

Build required reactor dependencies from the current sources. Confirm that every intended test actually ran; an empty selection is a failure. Report focused results as focused results, without claiming that a partial run passed the configuration gates.

Use one local JDK, normally JDK 17. The pipeline owns full-reactor verification and the JDK/operating-system compatibility matrix. Do not run local full suites or repeat verification across JDKs unless the user explicitly requests it. Reproduce an actual version-specific failure with a focused check on that version.

Repeat checks when relevant inputs change, a failure appears, or a new concern arises. Reuse results only when the relevant sources, tests, build, dependencies, environment, and scope match. For performance changes, start with one focused before/after measurement and repeat only if the result is noisy or inconclusive. Keep release and compatibility gates in CI.

For ongoing optimization work, a validated batch is a progress checkpoint, not a stopping condition. Continue through the ranked remaining candidates while supported, coverage-preserving improvements remain. Stop only on the user's instruction, a genuine external decision/blocker, or documented exhaustion of justified opportunities in the measured hotspots. Keep pending candidates and exclusion reasons recorded; waiting for CI's whole-build measurement does not by itself block further local work.
