# Correct resetDisableAllClientValidations Javadoc

- Status: Planned
- Priority: Medium
- Work: Source Javadocs, generated CLI verification

## Defect

The reset method references `DEFAULT_VERIFY_SERVER_IDENTITY`, so generated help reports an unrelated default for client-side validation.

## Plan

Reference `DEFAULT_DISABLE_ALL_CLIENTVALIDATION`, regenerate Javadocs and CLI help, and add a help snapshot assertion for the corrected default.

## Acceptance criteria

- [ ] Source Javadoc names the correct constant.
- [ ] Generated CLI help reports the correct default.
- [ ] Website validation terminology agrees with the corrected contract.

## Evidence

- Defect: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:628-634`
