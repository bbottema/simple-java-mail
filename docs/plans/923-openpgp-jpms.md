# OpenPGP named-module loading

The maintainer approved proof, repair and delegated review-workflow completion on 29 October 2026. Track the proven packaging gap under
[#923](https://github.com/bbottema/simple-java-mail/issues/923), following ADR 0006/0007/0031. The user-facing public API and common loader remain unchanged.

The issue lead owns the OpenPGP descriptor, packaged public consumer and Maven wiring, relevant ADR/index/release/website/OpenRewrite
companions, and exact cleanup targets. First establish the shared-loader failure from fresh JARs with the same-artifact classpath control.
Then grant qualified entry-package access and resolve the existing Kotlin runtime, exercising real signing/verification/encryption/decryption
and optional-absence behavior on Java 11 and 21. A separate reviewer inspects the final combined diff and evidence before delivery.

The baseline main/site heads were `b66b99b5d` / `bb705d8`. Independent proxy changes were retained through ordinary merges, preserving the
approved local ENVID ancestors, historical dirty files and the original staged patch. Use isolated reactor sources under
`.local/openpgp-jpms-20261029`; shared root targets are not used.

Design, execution and ordinary publication on `codex/10.0.0` and `codex/10.0.0-content` are authorized. Publish website first, retain a coherent
parent gitlink, use selective semantic commits and close/Done only after accepted completion. Keep all execution evidence in the maintainer
conversation or task logs, never GitHub issue prose or commit messages. No release, deployment, force push, history rewrite or deletion is included.

Completed on `codex/10.0.0` in `5b83c50219160f9ec63a3fa07477edb5dfd15305` and `codex/10.0.0-content` in
`c7c307f3355df0fe867230f4aaaedeb914503d92`. Issue #923 is closed/completed and Done. This retired untracked plan and its task scratch remain
on exact cleanup allowlists behind the existing node_modules reparse-point guard.
