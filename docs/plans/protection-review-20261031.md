# Protection review repairs, 31 October 2026

Retired after delivery: #931, #932 and #933 are closed/completed and Done. Main implementation and shared guidance are delivered through `37e99f67cbb370159bc814254a8d1854769b1ed0`; website guidance is delivered at `5891325e67940a88077b0fb744bda6113a097c00`. No implementation, review or issue work remains.

Only bounded disposal remains: automatic approval review rejected deletion of this round's scratch directory and completed plan with `blocked by policy`. Their exact paths remain in the cleanup allowlists. The full cleanup script also retains its existing stop at the older `.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. Preserve unrelated targets and guards.

The maintainer accepted all three proposed resolutions and delegated their complete review workflows, including reviewed delivery and issue closure. Publish the accepted changes on `codex/10.0.0` and applicable website changes on `codex/10.0.0-content`. No release, deployment or history rewriting is included.

Baseline: main `421f6c2f09ab6869718b28e9294a74b54d223027`, website `1c6ee92236eee183c24c554479d674403de47b21`. Preserve the existing staged and unstaged Angus research, cleanup script, website narratives/styles/tests, plans, scratch and linked worktrees. Baseline status, binary patches and indexes are retained under `.local/review-protection-20261031`.

Tracking: [#931](https://github.com/bbottema/simple-java-mail/issues/931), [#932](https://github.com/bbottema/simple-java-mail/issues/932), [#933](https://github.com/bbottema/simple-java-mail/issues/933). Each belongs to milestone 10.0.0 and the existing Simple Java Mail 10.0.0 project. `protection_docs` owns the shared documentation paths on the lead's behalf; workers retain their individual ADR ownership.

| Finding | Approved behavior and boundaries | Ownership |
| --- | --- | --- |
| Retained opaque S/MIME type parameters | Retain complete valid Content-Type metadata, including signed-data, without changing bytes, filename selection, ownership or cryptographic processing. | `opaque_artifact_fix`: SMIMESupport, new regression class, ADR 0036, issue workflow; scratch `opaque/`. |
| Detached signature filename collision | Keep distinct clear and signature source occurrences with their existing names; a filename is insufficient identity. Preserve the non-colliding representation without duplicate source occurrences. Historical views and protected bytes keep their contracts. | `signature_collision_fix`: ConfiguredEmailConverterImpl, new regression class, ADR 0008, issue workflow; scratch `collision/`. |
| Required DKIM null replacements | Reject explicit null domain/selector before mutation. Preserve passive inspection, omitted-field build behavior, non-null handling, optional defaults and module-owned signing validation. | `dkim_null_fix`: DkimConfig, new regression class, ADR 0031, issue workflow; scratch `dkim/`. |

Each worker creates its tracking issue in milestone 10.0.0 and the existing project, moves Todo to In Progress, implements with a focused failing regression, and runs relevant controls on Java 21 and real Java 11 in isolated source/target snapshots. Workers report concrete diffs and evidence to the lead, then receive a serialized commit/delivery turn. Completion comments start with final usage, reference delivered commits and ADRs, and distinguish development-branch delivery from release. Issues and commits contain no test or verification evidence.

The lead owns shared release history, README applicability, ADR index, website feature/migration guidance, Journal/case-study/example searches, OpenRewrite coverage, cleanup allowlists, final combined review and publication sequencing. Website delivery precedes the parent gitlink. Main and nested unrelated index/worktree changes must survive selective commits.

## Impact and verification order

1. Trace top-level opaque and detached S/MIME preprocessing through parser assembly, exposed resources, copying/serialization and ordinary export/reimport. Exercise standard/legacy media types, non-colliding controls and same-name distinct resources through real public converter entry points.
2. Exercise both DKIM setters before and after a valid choice, previous completed snapshots, passive inspection and retained omission/optional-default behavior. Do not move cryptographic validation or add build requirements.
3. Review source Javadocs, internal CLI adapters/caches, properties/Spring/locks, root/website guidance and released-versus-unreleased migration boundaries. Record no-change reasons where the affected public surface does not change.
4. Review workers' actual combined diff and Java 11/21 evidence; run additional integration checks only for unresolved interactions. Check generated public Javadocs for the DKIM documentation change.
5. Run website checks/build and internal links for changed guidance in an isolated website export; inspect rendered affected passages and relevant examples.
6. Serialize selective semantic commits, publish website before parent, verify remote heads, then let each worker complete its issue comment/closure/Done bookkeeping and verify labels/milestone/project/commit references.
7. Retire this plan after preserving maintained decisions. Update cleanup allowlists for exact task paths, validate syntax/dry run, and report any guarded cleanup blocker. No unrelated cleanup is authorized.

## Surface alignment

The individual ADRs, ADR index, unreleased release history, current security guide, released DKIM null-input migration guidance and both OpenRewrite inventories are updated. Existing README/features examples remain correct. Journal, case-study and downloadable examples contain no conflicting nullable DKIM calls or filename-identity assumptions; their unrelated current edits remain untouched.

There are no new signatures, settings, property/schema entries, Spring bindings, locks, dependencies, serialization formats or module descriptors. The existing scalar CLI adapter already requires DKIM identity inputs, and DkimConfigBuilder is outside the CLI root traversal, so neither CLI help nor its caches change. The send lifecycle/concurrency catalogue and infographic are unaffected. New guidance adds no executable snippet; the changed Java behavior is exercised through public converter and builder usage in the regression classes.
