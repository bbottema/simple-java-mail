Retired after development-branch delivery under [#958](https://github.com/bbottema/simple-java-mail/issues/958): source `a2eb1d9538ebd80924b74fa58908ebf52265b72d`, companion guidance `c046b507380db3accc02aef6ebb1c414e204fed2`, website `b30a0c4399c7fb83a645e750cdcf7b61a1fa564a`. The issue is closed as completed and its existing Simple Java Mail 10.0.0 project item is Done. This plan and the worker's exact scratch tree remain listed in the root cleanup script for later removal.

# Reused Session OAuth2 provider eligibility

The maintainer accepted finding 5 on 2 November 2026 and delegated the complete review workflow. Delivery to `codex/10.0.0` and applicable website `codex/10.0.0-content` is authorized after lead acceptance. No release, deployment, unrelated changes or history rewrite is in scope. [#958](https://github.com/bbottema/simple-java-mail/issues/958) owns this published-9.3.9 defect; #692 introduced the provider capability.

## Contract and impact map

A supplied Session's completed protocol and authentication mechanisms determine OAuth2 provider eligibility, irrespective of a retained historical strategy marker. Thus a Session reused after successful shutdown can switch from an ordinary preset to XOAUTH2 and register a lazy provider. Conversely a retained OAuth marker does not authorize a provider after selecting a non-OAuth mechanism. Owned-Session OAuth preset validation is retained.

- Capture/resolution: `ValidatedConnectionPlan.validateRequirements` and its internal `validateApplication` path already hold completed properties; give OAuth validation the owned preset only for owned Sessions.
- Application: `MailerImpl.configureSendingSession` uses the same owned-versus-supplied distinction, without changing resolution, Session mutation timing or connection settings.
- Preserve: explicit property conflicts and locks, protocol-specific selection, missing/conflicting credentials, lazy providers, supplied object identities, exclusive ownership and completed-shutdown reuse, ordinary SMTP and both owned OAuth presets.
- Public guidance: `MailerGenericBuilder.withOAuth2AccessTokenProvider` describes current supplied-Session mechanisms, including reuse; ADRs 0015/0038/0039 own those boundaries. No API/property/CLI/Spring signature is added.
- Scenarios: naturally reused non-OAuth Sessions switched through direct mutation and builder properties; SMTP/SMTPS; historical OAuth markers changed to non-OAuth mechanisms; missing credentials, fixed-token/provider conflicts, current-protocol isolation, inherited mechanisms, owned-preset controls, unchanged Session after rejected build and lazy callbacks.

## Ownership and order

The dedicated OAuth worker owns the two main validation callers, dedicated `ReusedSessionOAuth2ProviderTest`, narrow provider Javadoc, ADR 0039 clarification and the OAuth section of website `security.hbs` after lead coordination. The lead owns shared ADR index, README/release history, migration notes, OpenRewrite inventories and cleanup script. Other workers own daemon startup and DKIM guidance. Preserve their edits and all existing research/narrative work.

Scratch is bounded to `.local/review-selected-20261102/oauth/`. Use isolated source exports and Maven slot B; the daemon worker owns slot A. Read this baseline and write regressions first, establish the failing behavior, apply the narrow fix, then run focused controls on actual Java 11 and a modern JDK. Inspect Surefire XML rather than relying only on Maven exit status. Public Javadocs and affected rendered website guidance require review. Source and guide searches cover Journal, case studies, downloadable examples and both migration inventories, with applicability returned to the lead.

The worker returns concrete diff and evidence to the lead before selective commits, pushes or closure. Keep issue/project In Progress until accepted delivery. Issue and commit prose must contain no test/verification evidence; report that directly to the maintainer and retain execution records in owned artifacts.
