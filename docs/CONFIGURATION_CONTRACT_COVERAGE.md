# Configuration contract coverage

The configuration gate answers a bounded question: did this Maven invocation execute successful behavioral witnesses for every supported configuration route in the maintained inventory? It does not measure line coverage or prove every possible application value correct.

The inventory covers public setup overloads, canonical source properties, supported SMTP/SMTPS raw aliases, logical fields, selection rules and consumer adapters. Shared rule matrices establish precedence, explicit removal, inheritance, eligibility and retained provenance. Small route tests invoke each exact Java overload and assert its selected or captured value. Native Angus witnesses exercise its property access, parsing, factory lookup and trust interpretation; only the socket/resource handoff is intercepted. Deliberate SJM validation rules have separate expectations.

## Run the gates

From the repository root, use Java 11 for the library gate:

```shell
mvn -pl modules/simple-java-mail -am verify -Dlicense.skip=true
```

Use Java 17 or newer for the full reactor, including CLI, Spring and the Boot starter:

```shell
mvn verify -Ppublish-cli -Dlicense.skip=true
```

The library gate runs in the main module's `verify` phase. The full gate runs in the aggregation module after all integration modules, including the Boot starter. CircleCI's existing verification command runs both. A library result does not certify CLI or Spring coverage.

For a deliberately narrow debugging run, use `test` rather than `verify`, or explicitly pass `-Dconfiguration.contract.skip=true`. A partial test selection cannot satisfy the complete gate. Builds using `-DskipTests` or `-Dmaven.test.skip=true` skip the gate and produce no accepted coverage result.

The SMTP conformance runner explicitly skips this gate for its separately selected protocol suites. Normal CircleCI verification still requires
the complete configuration inventory before release approval.

## Read the result

Reports live under `modules/simple-java-mail/target/configuration-contract-coverage/run-<millis>/`. Each scope has a contract TSV, a count summary and a status file. Only `State: PASSED` with the current invocation's run identifier is an accepted result. CircleCI retains these reports with its artifacts.

JUnit records claims after assertions and publishes them only when the complete invocation succeeds, including lifecycle callbacks. An API claim comes from invoking its exact reflected public method; an annotation or inventory row alone cannot satisfy it. The gate matches claims to current-run successful Surefire cases, including the exact parameterized invocation. Missing, failed, aborted, skipped, stale, unbound and wrong-module evidence cannot supply coverage. Evidence files contain identifiers and source signatures, never configured values or credentials.

The fixed denominator combines the canonical `ConfigLoader.Property` and `EmailProperty` enumerations, the independent raw-alias access/grammar
inventories and these three main test resources:

- `configuration-api-routes.tsv`: the original exact public setup routes and their category owners.
- `configuration-extra-api-routes.tsv`: source/factory roots and additional reachable setup interfaces, value builders, `toBuilder()`/state-carrying copy factories and conversion boundaries.
- `configuration-contract-obligations.tsv`: logical fields, rules, adapter/case witnesses, mandatory regression methods and full-reactor integration contracts.

Their combined fingerprint binds witnesses to one inventory version. Runtime discovery independently checks public setup membership, source-property classification, both raw-alias access and grammar classifications, and generated CLI option membership. CLI options additionally have a frozen option/signature fixture in the CLI tests. Passing tests never define the denominator.

## Extend the contract

When adding or changing a configuration route:

1. Assign the logical field and feature-owned rule category. Preserve source loading in core and activation/resource effects in their existing owners.
2. Update the exact route/property/alias inventory, including reachable setup interfaces and optional consumers. For a raw alias, classify access and grammar separately.
3. Add a small behavioral wiring assertion for the new route. Reuse existing rule proofs when the semantics are unchanged; add a category proof when they are new.
4. Retain a concrete regression for a reported bug. Use an independent expected model or the actual provider, rather than the production helper under test.
5. Run the applicable gate. Changes to shared inventories require fresh evidence from all modules in that scope.

Gate negative controls exercise missing overloads and inherited interfaces, failed/skipped witnesses, incorrect parameterized cases, stale runs and changed inventory fingerprints. Keep these controls passing when changing evidence collection or report handling.

Copy routes retain explicit set/remove/inherit states and borrowed identities while producing an independent draft. They are included even when
the immutable holder itself has no setters. Neutral zero-argument value-builder factories do not carry a competing input choice; their produced
builders and state-carrying factory overloads remain in the required inventory.

Unknown provider properties retain pass-through coverage, not invented provider semantics. Application callback internals, arbitrary opaque objects, external infrastructure and unenumerated third-party providers remain outside the finite contract. Resource identity and ownership at their supported boundaries remain testable contracts.
