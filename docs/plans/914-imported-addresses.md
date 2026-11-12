Delivered on codex/10.0.0 at 929b1699cecf39708497ff5bab6dd4824335cbe5 and codex/10.0.0-content at 841614b4825117ccde6bb8c57119e36f71ccec41. Issues #914 and #915 are closed/completed and Done. Only guarded scratch/plan cleanup remains pending at the existing older node_modules junction; all original feature-review findings have been delivered.

# Preserve malformed imported address fields (#914)

Issue: https://github.com/bbottema/simple-java-mail/issues/914. Governing decisions: ADR 0035, ADR 0036 and ADR 0013.
The maintainer approved the concrete raw-Recipient fallback and delegated complete workflow delivery on 29 October 2026.
The lead reviews the actual combined diff before selective commits and publication on `codex/10.0.0` and `codex/10.0.0-content`.

Before: an otherwise readable MIME/EML with `Reply-To: broken <address` fails import.
After: when existing address interpretation throws an address-grammar exception, retain the complete offending field in an existing
tolerant Recipient. Applications can inspect body/resources and the invalid typed value; ordinary outgoing validation still sees it.
Never recover only into unchecked custom headers, guess a mailbox, retain a partially parsed list or introduce another public representation.

## Impact map

Execution paths: static/fluent MIME/EML and exact inspection -> existing `MimeMessageParser` address extraction -> current
`MimeMessageEmailMapper` Recipient mapping -> copying/serialization/ordinary preparation. Keep successful getter/parser paths intact.
To/Cc/Bcc already use Jakarta's non-strict header grammar; preserve that interpretation without strict preflight. Their cases are
compatibility controls unless a real existing grammar failure occurs. From and Reply-To retain default Session strictness, From's first
parsed mailbox and Sender fallback only when From is absent, and Reply-To's all-address From fallback when absent or parsed empty.
An explicitly malformed Reply-To retains its own source instead of becoming From.

Recover only AddressException at the actual parsing boundary. Whole-field source attribution must not use an exception reference that
may contain only a failing mailbox fragment. A custom getter exception with a valid raw field remains an error. Required content/codec,
protection, read, cancellation and unrelated programming failures retain their current contracts. Keep #838 Return-Path, receipt handling,
deferred RFC-group interpretation, stream ownership and strict exact envelope validation separate.

| Scenario | Intended behavior |
| --- | --- |
| Malformed From/Reply-To, valid neighboring fields and resources | Whole offending source remains typed and readable content survives. |
| Repeated fields / valid entries preceding a malformed entry | Preserve the complete existing comma-joined field; do not salvage a partial list. |
| Valid names, From lists, visible lists, duplicates, legacy delimiters | Preserve established parsing, cardinality and occurrences. |
| Absent/empty/comment-only fields and Sender/Reply-To fallbacks | Preserve current missing-value behavior and identify the actual failing source. |
| Copy/serialization, validate/rehearse, warning-only policy | Retain malformed typed values and run the selected outgoing validator. |
| Exact EML inspection and explicit envelope choices | Keep original bytes and reject malformed explicit envelope mailboxes. |
| Codec/content/read/programming failures | Propagate existing errors without broader recovery. |

## Ownership and surfaces

Worker owns `MimeMessageParser.java` and only necessary mapper changes, the focused `ImportedAddressRecoveryTest.java`,
the narrow ADR 0035 clarification and relevant converter Javadoc. Root owns release history, ADR index, OpenRewrite, shared website
pages and cleanup allowlists. A delegated test worker owns only the new test file; a read-only explorer checks Jakarta/source contracts.
Preserve all concurrent research/index/site work. No CLI daemon or SOCKS work belongs here.

Public converter class documentation should explain this import boundary; no builder signatures or Javadocs change by default.
If any CLI-generating input must change, coordinate genuine cache generation with root and the concurrent daemon worker first.
Account for Java/property/Spring/serialization/CLI, root docs, website, Journal/cases and downloaded examples. This is an import behavior
correction, so distinguish released behavior guidance from unreleased-only APIs in OpenRewrite rather than inventing source rewrites.

## Verification and delivery

Use exact scratch `.local/feature-review-address-import-20261029` and a HEAD export of
`101cfbb4ab460979dfa9d8500c43039e5229ecb7` plus owned overlays. Maven logs live outside targets within that scratch; never run against shared targets.
Observe focused public regression failures before production changes, then repair narrowly and run targeted current-source reactor controls
on real JDK 11 and a modern JDK. Existing Return-Path, receipt, managed-content, charset and exact-boundary suites supply neighboring controls.
Report checks directly to the lead; publish no verification evidence in issues or commits. Keep the issue In Progress until accepted delivery,
then add final usage, close completed/Done and read back metadata. Retire this plan and exact scratch through the guarded cleanup workflow.
