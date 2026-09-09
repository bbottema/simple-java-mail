# Receipt serialization fixtures

These are Java 11 `ObjectOutputStream` payloads, encoded as Base64. They exercise missing fields from older class definitions, which a round trip through the current class cannot cover.

- `receipt-accepted.base64`, `receipt-rejected.base64`, and `receipt-unknown.base64` were written using `MailSubmissionReceipt` from commit `ed49cccd`. That class has only `emailId`, `smtpResponse`, and `submittedAt`.
- `receipt-groups.base64` was written using the class from commit `207a7ece`. It adds status and accepted/unsent/invalid lists, but has no detailed recipient results or retry disposition.
- `receipt-rich.base64` was written using the pre-normalization phase-one implementation. It stores detailed recipient results and retry advice while leaving the compatibility lists null.

All fixtures use `serialVersionUID = 1L` and the timestamp `2026-09-08T12:00:00Z`. The tests document their synthetic recipients and responses. Do not regenerate the historical fixtures with the current class: that would remove the compatibility coverage.
