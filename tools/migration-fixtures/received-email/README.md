# Received-Email migration fixture contribution

This bounded packet contributes exact released/target symbols and source-preserving review examples to the OpenRewrite tooling work in
[#766](https://github.com/bbottema/simple-java-mail/issues/766), for the received-message continuation of
[#961](https://github.com/bbottema/simple-java-mail/issues/961). It is a fixture corpus and compiler/descriptor check, **not** a production
OpenRewrite recipe, scanner or report companion. The wider tooling and release gates remain in the
[implementation plan](../../../docs/research/10.0.0-openrewrite-migration-implementation-plan.md).

The released input is the published/tagged **9.3.10** API. Existing inventories retain their earlier 9.x witnesses. Do not put incompatible
core versions on the same classpath, resolve a moving worktree as the target or silently use an installed stale JAR. Supply the complete
explicit classpaths for a released artifact set and a frozen final target artifact set; paths must identify JARs. The runner records the
artifact identity in its caller-selected evidence directory and does not download, install or mutate either artifact set.

`targetRelease` names the intended release, not a claim that those coordinates are published. The development POM can retain its existing
version until deployment; the explicit candidate JAR identities recorded by the runner govern this check without changing project versions.

```powershell
python tools/migration-fixtures/received-email/check_fixtures.py `
  --released-classpath "$releasedCore;$releasedMailApi;$activationApi" `
  --target-classpath "$frozenCore;$targetMailApi;$activationApi" `
  --evidence-dir "$taskEvidence\received-email-migration" `
  --jdk-home "$jdk11"
```

Use actual file paths for these variables. The chosen JDK must provide `javac`/`javap` and support `--release 11`. The runner compiles
untouched released sources, compiles the explicit manual target and same-name negative control against the frozen target, and checks
versioned supported public descriptors. Package-private serialization-only shells do not restore a removed application API. The released legacy consumer is also expected to fail against the target: that failure demonstrates a removal,
not a complete automatic migration. No recipe is run and no produced transformation is claimed. Hashes before/after ensure compilation
does not change the fixture sources; expected report rows remain declarative contributions for the real tooling track.

| Files | Purpose |
| --- | --- |
| `symbols.json` | Exact released descriptors, continuing native-body getters and required/removed target symbols. |
| `released/LegacyReceivedEmail.java` | Compiling 9.3.10 source with actual semantic removals; preserve this source in report-only mode. |
| `released/NativeBodies.java` | Published native HTML/RTF getters that must continue to compile in the target. |
| `manual/PartnerDocument.java` | Application chooses a document occurrence and approves keys, then processes/holds that selected payload; no inferred index/key. |
| `manual/OriginalAndInterpreted.java` | Explicitly different forwarding intents and conversion-local disclosure consent. |
| `negative/SameNamedApplication.java` | Unrelated application methods with legacy names must never match an SJM method detector. |
| `expected-findings.json` | Required R028/R033/R040 findings, guide links, source-preserving/manual disposition and negative controls. |

Manual review must distinguish body envelopes from independent attachments/children, selected readable payload from complete original,
and one fully covering verified approved signature from unrelated successes. A clean parse or a claimed From address does not approve a
signer. New field/family inspection, OpenPGP result refinements and operation-consent APIs are target fixtures rather than invented 9.x
migrations. Never insert disclosure permission, select attachment zero or replace a nullable signature Boolean automatically.

An original-forward target is not automatically a permission to export cleartext. A child's original extracted from encrypted parent
content can lack that parent's envelope, including protection of child outer headers that its own body encryption leaves exposed. The
helper preserves actual bytes/format while requiring suitable outgoing protection or deliberate conversion-local consent. An intact
root encrypted original remains distinct; signing alone is not confidentiality. Neutral automatic carrier metadata does not redact the
original getter. These are target behavior controls under existing report responsibilities, with no new original-forward overload or mode.

The unchanged `Email.getSubject():String` descriptor also has a runtime review case: released ordinary MIME import replaces a missing
subject with an empty String; the target leaves genuine absence/unavailability null and preserves explicit empty. Review actual import
consumers without treating every already-nullable getter use as a migration site or inserting field-filling behavior automatically.

The fixture compiler does not prove runtime decryption, coverage, snapshot ownership or final-output enforcement. Those belong to the
owning library regressions and runnable protected-partners application. Use their accepted final artifact set for this compilation packet.
Read [received-mail migration](https://www.simplejavamail.org/migration-notes-10.0.0.html#received-email-reading),
[helper confidentiality](https://www.simplejavamail.org/migration-notes-10.0.0.html#received-email-helper-confidentiality) and
[header reuse](https://www.simplejavamail.org/migration-notes-10.0.0.html#received-header-reuse) for the actual caller decisions.
