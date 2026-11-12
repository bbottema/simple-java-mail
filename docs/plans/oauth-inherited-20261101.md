# Inherited fixed OAuth2 token activation

Retired after delivery: [#938](https://github.com/bbottema/simple-java-mail/issues/938) is closed/completed and Done in milestone 10.0.0. The two activation corrections, regression and ADR are delivered in `9bc394d0591a3ff1b5e65518c19b07e74732270e`; shared main guidance is delivered through `6e0b9b7e0f474ac58a2241ac6251de51b62c915e`, and website guidance through `3f65b066685f581b8055fca75bb9baae7e7c0942`. Final main publication is `0258894ec42d6e077b5d617406299c933645dfc9`, including the exact cleanup allowlists and manual commands. No implementation, review, documentation or issue work remains.

Only bounded disposal remains. Automatic approval review rejected deletion of this exact task scratch directory and retired plan with `blocked by policy`; no deletion occurred. Their allowlist entries and simple exact-path deletion statements are retained in the cleanup script for a later manual run, as required by the current review guide. The full script dry run separately stops at the older `.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. Preserve existing guards and unrelated targets.

On 1 November 2026 the maintainer accepted the proposed fixed-token lookup correction and said to implement it and finish the workflow. This authorizes delegated execution, reviewed selective commits, publication on `codex/10.0.0` and applicable `codex/10.0.0-content`, and issue completion. No release or deployment is included.

Baseline: main `ea2d9ced6ef94a32ce0bf776895af002e287fca3`, website `ce9f02872ca09392714c1647091468703c30778c`. Status, original indexes, patches and affected-file copies are under `.local/oauth-inherited-20261101/preservation`. Preserve existing Angus work, nested website edits, retired plans/scratch and the maintainer's unstaged cleanup instruction in `docs/API_REVIEW_WORKFLOW.md`.

Tracking: [#938](https://github.com/bbottema/simple-java-mail/issues/938), milestone 10.0.0 and the existing Simple Java Mail 10.0.0 project; execution is In Progress. Released 9.3.9 has the same direct-key activation gate and also rejects an inherited-only fixed token during its earlier validation. The target has already aligned validation; this repair completes runtime activation. Describe the released behavioral correction without implying that released construction accepted the target's reproduction.

## Scope and paths

`TransportConnectionHelper.usesOAuth2Authentication` and the optional module's `BatchTransportEngine.bridgeOAuth2Properties` currently check direct key membership for a fixed token. Validation and token resolution use String-property visibility, including `Properties` defaults. Use `getProperty(OAUTH2_TOKEN_PROPERTY) != null` at both activation boundaries, keeping direct-entry lookup for the callback object. Do not materialize the inherited token into its canonical direct key, read callbacks early or add an API/configuration option. Existing internal pool aliases retain their role.

Direct send reaches `AcquiredMailTransport` and `connectForSending`; connection checks reach `TransportRunner` and `connectTransport`; authenticated probes reach `SmtpConnectionProbe` and the same connection helper. With the batch module, pool registration first bridges credentials to the existing pool alias, so its own fixed-token gate must use the same String visibility. Keep validation, provider errors, authentication failures, caller-owned entries/defaults and ordinary non-OAuth connections under their existing contracts. The core `TransportStrategy.toString(Properties)` direct-entry diagnostic formatter is outside this runtime correction.

| Scenario | Required behavior |
| --- | --- |
| Direct or inherited fixed String token | Forward the recognized token and username to transport connection. |
| Inherited String behind an opaque direct slot | Retain existing provider-visible String interpretation under ADRs 0038/0039. |
| Direct callback provider | Remain lazy and resolve for each physical connection using the existing direct-object contract. |
| No OAuth credential | Keep ordinary provider connection behavior. |
| Explicit conflict or provider/authentication failure | Keep current validation, cause and failure boundaries. |

## Ownership and verification

`fix_cli_profiles` owns the connection helper, batch bridge, focused public-route regression/controls, ADR 0039 and issue lifecycle. Start with a behavioral failure, then run focused checks on real Java 11 and 21 in isolated source snapshots under `code/`. The existing scripted SMTP/TLS fixtures permit actual send, testConnection and authenticated-probe checks for SMTP/SMTPS without external accounts. Confirm source freshness after overlaying the fix. No shared targets or full-suite claim.

`followup_docs` owns release history, ADR index, both OpenRewrite inventories and applicable root/website guidance. Inspect released 9.3.9 before describing compatibility consequences. Review README, Journal, case studies and downloadable examples in context. No signatures/settings/CLI help changes are planned; record any generated-artifact implications or no-change reasons. Validate selected website changes in an isolated export under `docs/`.

The lead reviews actual combined changes and check evidence, serializes selective commits, publishes website before the parent gitlink, verifies remote heads and issue metadata, and preserves the pre-existing index/worktree changes. GitHub issue and commit prose contain behavior, usage and ADR references, never verification evidence. Completion comments start with final usage and distinguish development-branch delivery from release.

Stop owned helpers and retire this plan after delivery. Record exact disposable paths in the cleanup script, retain its guards, validate syntax/dry run and report any blocker. If cleanup stalls, include simple deletion statements for the exact remaining paths for a later manual run, as required by the current review guide. Do not run earlier rounds' cleanup.
