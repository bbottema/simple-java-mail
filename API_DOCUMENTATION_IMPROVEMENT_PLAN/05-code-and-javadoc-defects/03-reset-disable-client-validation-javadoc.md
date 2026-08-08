# Correct resetDisableAllClientValidations Javadoc

- Status: Done
- Priority: Medium
- Work: Source Javadocs, generated CLI verification

## Defect

The reset method references `DEFAULT_VERIFY_SERVER_IDENTITY`, so generated help reports an unrelated default for client-side validation.

## Plan

Reference `DEFAULT_DISABLE_ALL_CLIENTVALIDATION`, regenerate Javadocs and CLI help, and add a help snapshot assertion for the corrected default.

## Acceptance criteria

- [x] Source Javadoc names the correct constant.
- [x] Generated CLI help reports the correct default.
- [x] Website validation terminology agrees with the corrected contract.

## Evidence

- Defect: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:628-634`
- Source Javadoc now describes the observable result—restoring blocking validation—and resolves `DEFAULT_DISABLE_ALL_CLIENTVALIDATION` to `false`.
- The JDK 8 `publish-cli` profile regenerated `cli.data` and `therapi.data` from the corrected builder documentation.
- `GenerateCliHelpTest` reproduces the old `true` output and now asserts that packaged help reports blocking validation with default `false`.
- The existing website example already says `resetDisableAllClientValidations()` restores blocking validation; no website edit was required.
- Verification: `mvn -pl modules/cli-module -Dtest=GenerateCliHelpTest test` passed on JDK 8 after metadata regeneration.
- Implementation and metadata commit: `d45e06aa fix(cli): report validation reset default correctly`.
