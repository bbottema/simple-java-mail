# Selecting verification

Choose tests for the behavior and boundary affected by a change. The table is a starting point, not an automatic selector; inspect the affected code, tests, and build when choosing a narrower or broader scope.

Use one local JDK, normally JDK 17. Full-project verification and checks across JDK versions and operating systems belong to the pipeline. Do not repeat the full suite locally on JDK 11, 17 and 21. An actual version-specific failure can justify a focused reproduction on that version.

When switching JDKs, rebuild the affected reactor dependencies with the selected compiler. Changing `JAVA_HOME` alone can leave cached classes from the previous JDK; coverage comparisons also require matching production class files.

| Change | Default development checks |
| --- | --- |
| Investigation or documentation | Relevant inspection or documentation checks. Run Java tests when tested or packaged content is affected. |
| Localized behavior fix | Run a focused baseline, the regression and neighboring controls, and the nearest integration boundary. |
| Shared configuration, public API, provider boundary, or cross-module lifecycle | Expand to affected modules and applicable contract and consumer checks. |
| Test consolidation or runtime improvement | Check the affected tests and nearest boundaries on the local JDK; leave complete verification to CI. |
| Release | Confirm the pipeline's required full and compatibility checks passed for the release revision. |
| Explicit request for a local full verification | Run the requested scope and JDK; do not expand to additional JDKs automatically. |

Tests for shared core behavior can live in `modules/simple-java-mail`, even when the implementation lives in `modules/core-module`. Use `-am` to compile required reactor dependencies from current sources. The following examples run on JDK 17 or newer from the repository root; adjust the selection to the change:

```powershell
# Pool configuration: resolver, public Mailer wiring, pool and batch boundaries
mvn -pl modules/simple-java-mail -am test "-Dtest=ConfigurationResolverTest,LayeredMailerConfigurationContractTest,MailerConfigurationWiringTest,PoolConfigurationContractTest,BatchConfigurationResolutionTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# MIME conversion: public conversions and parser
mvn -pl modules/simple-java-mail -am test "-Dtest=EmailConverterTest,FluentEmailConversionTest,MimeMessageParserTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# CLI parsing and generated option bindings
mvn -pl modules/cli-module -am test "-Dtest=CliCommandLineConsumerTest,CliRepeatedBuilderOptionsTest,CliConfigurationInputContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Shared configuration: core input checks and main-module consumers
mvn -pl modules/simple-java-mail -am test "-Dtest=ConfigLoaderRequiredInputsTest,ConfigLoaderTest,ConfigurationResolverTest,SimpleJavaMailFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

The Surefire option allows upstream reactor modules without a matching test to continue; it can also hide a misspelled or misplaced selection. Check the current run's output or fresh Surefire reports to confirm that **each intended class ran with cases**, and treat a missing or zero-case class as failure. Add CLI process or packaged-distribution checks when those boundaries are affected. A focused `test` run does not establish complete configuration-contract coverage. A partial `-Dtest` run reaching `verify` needs `-Dconfiguration.contract.skip=true` and remains a partial check.

The pipeline owns the full JDK 17+ checkpoint (`mvn clean verify -Ppublish-cli`) and the real JDK 11 library lane (`mvn -pl '!modules/cli-module,!modules/jacoco-aggregator-module' verify`). Existing [CLI daemon](../.github/workflows/cli-daemon.yml) and [Spring Boot compatibility](../.github/workflows/spring-boot-compatibility.yml) jobs cover the configured JDK/platform combinations. Keep existing release and CI obligations, including SMTP conformance, as documented in [MAINTAINER_WORKFLOW.md](MAINTAINER_WORKFLOW.md) and [DEVELOPMENT.md](DEVELOPMENT.md). These commands are not routine local development checks. On affected Windows checkouts, add `"-Dlicense.skip=true"` as described in the development guide.

For performance work, compare the same focused selection before and after on the local JDK. Record actual cases, suite timings and relevant fixture/process starts; Maven wall time can also include compilation and metadata work. Start with one comparison and repeat only when noise or a failure requires it. Obtain the whole-reactor timing from the next normal pipeline run rather than repeatedly running all tests locally.

JaCoCo instruments the project namespaces `org.simplejavamail.*`, `testutil.*`, and `demo.*`. This keeps project and fixture probes while avoiding instrumentation of unrelated libraries. Align the agent filter with any new owned namespace, including generated classes; compare compiled class identities and project probe hits when changing this filter. The aggregate report's module list is a separate scope and does not include every reactor module.

Repeat a completed check if relevant sources, tests, build inputs, dependency artifacts, environment, or scope change, or if a failure or new concern appears. Reuse earlier evidence only when those relevant inputs match. Report the selection and its limits accurately; do not describe a focused run as a full reactor or configuration-gate pass.
