# #764: Tactical and strategic email integration patterns

Accepted plan, 2026-10-16. Delivery authorized on 2026-10-23, independently trackable from [#763](https://github.com/bbottema/simple-java-mail/issues/763).
Tracking: [#764](https://github.com/bbottema/simple-java-mail/issues/764).
Verification: [application matrix and limitations](../../tools/integration-patterns/VERIFICATION.md).

## Website structure

Add a Patterns hub. Tactical Patterns solve focused SMTP/API problems; Strategic Patterns demonstrate application architecture.
Use textual labels/badges in headers/cards/grouped Docs navigation from shared validated metadata, never color alone.
Preserve the workload field guide's title, URL, directory, all 26 anchors and group-only rail. Strategic pages get their own suitable schema/layout.
Keep reference docs, Journal and Case Studies separate. Cross-link strategies to tactical building blocks and feature references.

## Runnable portfolio

| Problem | Primary stack | Runnable variation |
| --- | --- | --- |
| Committed transaction triggers tracked notification | Spring Boot, PostgreSQL, JobRunr | — |
| Events drive communications | Kafka, PostgreSQL | RabbitMQ |
| Long-running process reacts to mail feedback | Temporal | Camunda |
| Interrupted nightly run resumes safely | Spring Batch, PostgreSQL | — |
| Partners exchange protected documents | Apache Camel, local mail/archive | — |
| Incoming mail becomes application data | Spring Integration, application HTTP endpoint | — |

Patterns lead; technologies are a secondary index, not duplicate tutorials. Each chapter explains ownership, flow, SJM acceleration, concrete results
and failures. Applications/frameworks own durable work, scheduling and business state. Project mail facts into application DTOs; do not persist Mailers,
private keys or internal report implementations. Show duplicate input, partial/uncertain acceptance, delayed DSN and absent correlation without exactly-once
SMTP claims or blind replay. SMTP and a database/broker cannot participate in one atomic commit.

Canonical sources: `tools/integration-patterns/`, outside the published reactor. Generate website downloads from these sources.
Docker Engine/Compose only on host; build current SJM and apps inside containers. No host JDK/Maven/Python, real mail account, external delivery,
mandatory paid/cloud services or dependence on unpublished Central artifacts. Use pinned images/dependencies, health checks, loopback ports,
project-scoped networks/volumes/cleanup, synthetic `.test` mail and clearly labelled simulated DSNs. Document build/resource/license/architecture limits.
Keep heavyweight UIs optional. Provide consistent up/scenario/status/reset commands, local end-to-end tests and observable persisted results.

## Verification and delivery

Run primary apps and variations with local services, not only compilation. Check resumption/idempotency and uncertain-submission handling.
Run metadata/navigation/accessibility/anchor/download tests, website checks, clean build and internal links. Keep heavier Compose checks separate
from library tests. Update feature guidance, README/release history and comparison companion only to the verified level; website prose is version-neutral.

Follow the frontend-design skill using the existing site identity. No new SJM runtime framework or concurrency layers. Preserve concurrent MIME/PIPELINING,
artwork, website/Journal/Case Study work. Leave changes uncommitted; do not push, close either issue or mark Done.

## Accepted extension: executable case studies, alternate handoff and AWS

Accepted 2026-10-17. The original six chapters above remain implemented; the following extension is also implemented locally and awaits review.

- [x] RelayDesk: Docker application, PostgreSQL and authenticated per-tenant SMTP fixtures; unchanged article helpers compiled into the app.
- [x] Polar Meridian: two dispatcher instances, shared PostgreSQL claims/allowance, urgent/routine/bulk isolation, approved failover, protected archive and optional telemetry.
- [x] CustomMailer: HTTPS handoff and a runnable RabbitMQ variation. Preserve prepared MIME plus envelope; no SMTP server or SMTP-acceptance claim.
- [x] AWS protected correspondence: the same containerized worker locally and on Fargate; SQS, S3, DynamoDB, SES SMTP, SNS feedback, Secrets Manager and scoped IAM.
- [x] Equivalent Terraform/CloudFormation foundation and worker deployments, scoped scripts and actual isolated AWS verification.
- [x] Curated capability-to-demonstration registry, reference-section links and reciprocal pattern links, backed by executable scenarios.

Keep case-study prose and helper classes intact except supplementary exploration links. Consume canonical helpers in builds/downloads rather than maintaining
forks. All application machinery stays outside published SJM modules. Reuse IBM Plex/teal styling and existing category metadata; meaningful accessible links,
not indiscriminate badges. Website prose remains version-neutral.

AWS scope: ECS/Fargate (not Lambda), protected documents and archive (not only notifications), Terraform plus CloudFormation (not Cloudscape UI).
The user authorized actual AWS prototyping. Use unique run names/tags and an exact resource manifest. Reference the existing verified SES identity read-only;
never modify production infrastructure, DNS, identity defaults, feedback subscriptions, suppression settings or existing IAM policies. Use demo-only SMTP
credentials, keep secrets out of logs/state/downloads, and send only to SES mailbox simulator addresses. Run deployment formats sequentially and clean up
only resources demonstrably owned by that run. Report resource-derived cost estimates and local-versus-cloud evidence separately. No fixed dollar threshold
was selected: architecture and test scope govern the prototype, not an arbitrary budget tier.

Local AWS fixtures: ElasticMQ, DynamoDB Local, S3Mock and scripted SMTP; injected SES JSON is not a MIME delivery report or proof of AWS conformance.
Implement preflight/plan/deploy/scenario/status/destroy, review changes before provisioning, and record cleanup verification. No public API/UI, ALB, NAT gateway,
RDS, inbound SES reception or account-wide quota coordinator is included.

## Extension delivery status

Implemented locally, awaiting review. All 14 local configurations passed, including the original framework variations and the Collector profile.
Real Terraform/CloudFormation runs reached simulator SMTP acceptance plus delivery/bounce/complaint events. The source download contains canonical helpers
and both infrastructure formats, never deployment state/credentials. Read the verification record for the exact lanes and completed cleanup.
The cloud image uses a separate target without the local fixture's TLS keys or trust anchor. Current reference prose remains version-neutral.
No new library API, published framework/AWS dependency, commit, push, issue closure or Done transition is part of this delivery.

## Accepted review and teaching pass

The user accepted the full review on 2026-10-17, with particular emphasis on a clear entry point for every pattern.

- Give all ten READMEs and website chapters the same practical route: obtain the matching checkout, run the exact Docker commands,
  inspect the expected result, follow one job through named source methods, try a meaningful failure/variation, then clean up that project.
  Link the shared directory and website hub directly to those starting points. Keep optional AWS/Collector setup separate.
- Apply the coding guide to the application code, scenario clients and supporting infrastructure. Explain the SMTP/transaction, claim,
  acknowledgement, protection and resource-ownership boundaries in plain English; keep narrative methods separate from mechanical leaves.
- Persist authoritative terminal outcomes instead of overwriting receipts from an exception catch. Acceptance can coexist with a held job
  after cleanup fails. Make failed feedback handoff and current-job result-storage failure visible without adding automatic replay.
- Correct capture-fixture evidence: require complete DATA, reset envelope state, match tenant domains exactly, fence STARTTLS socket replacement
  against a concurrent drop, and assert partial versus unknown acceptance precisely. Add focused application and fixture regressions.
- Own and close all application resources, including startup rollback. Readiness must mean the application can do its job: RabbitMQ's Erlang
  ping alone is insufficient, and a gateway must not expose healthy HTTP endpoints before its broker consumer is registered.
- Repeat the complete local application matrix after the corrections, including restart/recovery guards and telemetry evidence. Recheck cloud
  template structure/formatting and deployment guards without provisioning more AWS resources for this teaching pass. Regenerate the source
  download after the final code and verification record, then rerun website and link checks.

The dated [verification record](../../tools/integration-patterns/VERIFICATION.md) records the final results and limitations. These changes remain
uncommitted for review; unrelated library research and concurrent website/Journal work remain outside this pass.

## Final holistic review corrections

- Require recorded foundation resource identities and both ownership tags before purging S3 objects or ECR images. Validate both targets before any
  destructive cleanup, recover interrupted inventory only from owned state/stack IDs, and exclude failed CloudFormation creations from ownership.
- Include the approved bulk relay when inspecting protected Polar Meridian archives. Exercise protected bulk and urgent messages in both ordinary
  and Collector scenarios; select an actually submitted bulk job rather than assuming concurrent dispatch preserves enqueue order.
- Retain these corrections and their regression evidence uncommitted with the rest of #764. They do not change #763's library implementation or status.

## Authorized delivery, 2026-10-23

The maintainer authorized committing the pattern work and pushing both repositories. This supersedes the earlier review-only delivery limits.

Commit the pattern pages, opening navigation cards, Tactical Patterns documentation frame, runnable application sources, generated download,
shared Polar Meridian configuration helper and its checks, and the supplementary links from the two case studies. Keep the unfinished Journal
articles, substantial case-study rewrites, concurrent public-API repairs and Angus research outside these commits. The website is committed
independently before the parent records its new revision alongside the runnable sources.

Validate the selected files on the committed library baseline in an isolated checkout, refresh the source download, and preserve unrelated
staged and unstaged work in the original checkouts. The dated verification record separates this delivery's checks from earlier cloud runs.
