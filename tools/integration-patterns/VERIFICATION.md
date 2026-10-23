# Strategic Patterns workbench verification

Date: 2026-10-17. Tracking: [#764](https://github.com/bbottema/simple-java-mail/issues/764), using the local [#763](https://github.com/bbottema/simple-java-mail/issues/763) implementation.
Uncommitted source and documentation await review. No issue is marked Done.

## Environment and scope

- Windows host; Docker Desktop Linux engine, x86_64, 16 virtual CPUs and approximately 15.5 GiB available engine memory.
- The original six framework applications and four extension applications build inside pinned Maven/Temurin images from the matching SJM checkout.
  No host Java/Maven/Python is required by the local demos; the cloud runner has separate prerequisites.
- PostgreSQL, brokers, workflow services, SMTP capture/fault service and scenario clients are real local containers. The fixture never forwards Internet mail.
- Primary services publish only loopback HTTP ports. A host HTTP health request returned UP; raw SMTP, databases and brokers remain unpublished.
- Cold-cache build time, peak resource consumption, ARM behavior and production deployment are not measured or claimed. The README's resource guidance
  is planning headroom, not a tested minimum. Image/dependency downloads dominate an uncached first run; later examples reuse the build/cache.

## Executed application matrix

| Application | Framework actually exercised | Local scenarios |
| --- | --- | --- |
| transaction-outbox | JobRunr with PostgreSQL | Happy, partial, uncertain, delayed; stable job identity and committed SMTP guard |
| event-communications | Kafka with PostgreSQL | All four scenarios and duplicate business input |
| event-communications variation | RabbitMQ with PostgreSQL | All four scenarios through the queue listener |
| mail-workflow | Temporal with PostgreSQL | All four scenarios; report-to-workflow signal and persisted workflow decision |
| mail-workflow variation | Camunda/Zeebe with PostgreSQL | All four scenarios; BPMN jobs, correlation and persisted workflow decision |
| restartable-batch | Spring Batch with PostgreSQL | All four scenarios, plus deliberate interruption after two submissions and resumption to five total |
| protected-partners | Camel, S/MIME and local byte archive | All four scenarios; received signature verifies before the captured bytes are archived |
| incoming-feedback | Spring Integration and local HTTP endpoint | All four scenarios; application DTO becomes JSON and reaches the business endpoint |

Each four-case run checks actual SMTP content-submission counts and durable application evidence. Repeated business input produces one content submission;
partial and lost-final-reply cases remain held, rather than being replayed by the framework. The scenario explicitly injects a synthetic DSN, checks exact
duplicate suppression, leaves missing correlation unactioned and retains a final failure when a stale delayed notification arrives.

The outbox restart check separately seeds a stranded SENDING intent, restarts the application, observes HELD_AFTER_RESTART and confirms that repeating
that job does not submit SMTP. This exercises the recovery guard, not a claim to reproduce every crash instruction between SMTP and SQL commits.
The batch interruption deliberately occurs after committed submission evidence: resumption emits three more messages, not five more.

All primary and variation scenario runs passed. All six primaries were repeated successfully after the last functional correction, including the
batch resumption and outbox restart guard. The local host smoke check uses 127.0.0.1 and a reasonable HTTP timeout; localhost's IPv6 fallback on
this Windows host exceeded an initial two-second polling timeout even while the application was healthy. No application behavior was weakened to fix the probe.
Disposable Compose projects/volumes are cleaned with their own manifests; no global prune or unrelated container cleanup is used.

## Corrections found by running the apps

- The Temporal and Zeebe clients initially selected incompatible gRPC components; the workflow app now aligns that dependency family with a BOM.
- Kafka's executable also contains the Rabbit variation. The unused Rabbit health contributor is disabled for Kafka and enabled for Rabbit.
- Spring Integration initially chose form encoding for the DTO map; the flow now uses its JSON transformer before the HTTP gateway.
- Batch interruption state is step-owned rather than a shared writer counter; the restart reader uses committed READY evidence, not a stale cursor index.
- SQL failure after successful submission is outside the SMTP exception catch. The committed guard remains authoritative and acceptance is not relabelled
  as a provider failure just because persisting the result failed.
- Runtime networks are ordinary project-scoped bridges, not falsely described as egress-blocked networks. Only loopback HTTP ports are published.

## Reproduce

Use the commands in each README. Run a primary and its variation sequentially, using both Compose files for startup, scenario and cleanup.
The portable scenario container accepts all/happy/partial/unknown/delayed; the batch additionally accepts restart.
Run `package_examples.py` in the pinned Python container to generate the source ZIP, then use `--check` to reject download drift.
Website verification uses `npm run check`, a clean `npm run build` and `npm run verifyLinks:internal`.

The original local matrix's raw logs are in ignored `.local/delivery-reports-verification/`; they are not runtime dependencies. That lane involved no
performance benchmark, Internet delivery, public receiving endpoint, hosted cloud subscription or exactly-once SMTP guarantee.

## Executable case-study and alternate-transport extension

The extension was exercised on 2026-10-17, still uncommitted for review. It adds no production dependency or module to SJM's published reactor.
The unchanged canonical article helpers are compiled into the applications and included at their original paths in the source download.

| Configuration | Evidence actually checked |
| --- | --- |
| RelayDesk | Authenticated tenant A/B routes; A blocked while B completes; duplicate job input; credential retirement/replacement; failed replacement pauses only A; subsequent valid replacement resumes it; partial/uncertain results held; cross-tenant feedback cannot correlate |
| Polar Meridian | Two processes claim shared PostgreSQL work; combined bulk allowance stops at two; urgent work is not starved; A→B→A relay recovery; protected archive decrypts/verifies and matches captured SMTP bytes; injected result-write failure is visible |
| Polar Meridian + Collector | Actual OTLP spans and Prometheus histograms/counters; six logical completions across two distinct SDK resource-instance identities; no per-email IDs or recipient addresses as metric labels |
| CustomMailer → HTTPS | No SMTP service; prepared S/MIME verifies/decrypts; separate hidden envelope; duplicate package suppressed; explicit rejection and lost confirmation are held; successful callback retains UNKNOWN SMTP acceptance |
| CustomMailer → RabbitMQ | Confirmed durable publication plus independent consumer/storage; duplicate protection; invalid package retained in its dead-letter queue rather than discarded |
| AWS worker, local fixtures | ElasticMQ/DynamoDB Local/S3Mock/TLS SMTP; duplicate queue input produces one captured submission; protected archive verifies; duplicate/out-of-order feedback; rejected/uncertain attempts held; uncorrelated feedback redrives to its DLQ |

Together with the original eight configurations, these are **14 Docker configurations**, not 14 SJM execution modes. The original six primary applications
and both framework variations were rerun after aligning the supported mail-provider, Bouncy Castle and OpenTelemetry dependency families.
All application scenarios passed. Source-download and cloud safety tests are separate from these networked scenarios.

## Actual AWS prototype

Actual, isolated AWS resources were provisioned in the commercial `eu-central-1` region using the explicitly selected account/profile. No account ID,
credentials, infrastructure state or actual resource ARN belongs in this published record/download. Per-run manifests and raw evidence remain local.

Both deployment formats completed foundation/worker creation and actual SES mailbox-simulator scenarios. Each scenario posts duplicate work for success,
bounce and complaint recipients, waits for SMTP acceptance and correlated SES→SNS→SQS events, and checks the archive hash and AES256 object-encryption facts.
SES events are provider JSON, not MIME delivery reports. Simulator feedback does not demonstrate a partner decrypting content or final real-world delivery.

The deployment creates only run-scoped infrastructure. Terraform plans permitted additions only, with no imports, updates or deletion of pre-existing
resources. Cleanup validates ownership and recorded IDs, removes only that run's work/data/images/SMTP credential, and checks the existing SES identity
is unchanged. Native ECS INACTIVE records may remain as non-running history; these are distinguished from live resources rather than falsely promising
that AWS immediately erases every historical ARN. A read-only supplied-network Terraform plan was also checked; borrowing a network was not deployed.

The final image split keeps local TLS keys/trust out of the Linux/x86_64 cloud target; only synthetic S/MIME material remains. A container check verifies
the absence of the development TLS files and trust-store alias before publication. The cloud-only target was exercised in the final CloudFormation run
and repeated through Terraform after that separation. The latter also checks the encrypted MIME media type and one observed SES submission ID per job.
The terminal-outcome persistence correction was then compiled with its six application tests and deployed through Terraform; all three simulator cases
passed again. All six provisioned prototype runs were removed with their recorded manifests; the separate supplied-network plan created no infrastructure.

The verification sequence passed all 14 local configurations, eight isolated tooling tests, six application-only JUnit tests, 92 website checks,
a clean site build and 5,324 internal links across 80 pages. These are local development results; they are not a hosted CI result or a release of the
uncommitted implementation.

### Cost reference, not a bill

The AWS Price List API queried on 2026-10-17 returned these Frankfurt on-demand rates:

| Component | Configured amount | Hourly calculation, USD |
| --- | --- | --- |
| Fargate CPU | 0.25 vCPU | 0.25 × 0.04656 = 0.01164 |
| Fargate memory | 1 GiB | 1 × 0.00511 = 0.00511 |
| In-use public IPv4 | One address | 0.005 |
| Running task + address | One steady worker | **Approximately 0.02175/hour** |

This is not the total charge: ECR storage/scanning, Secrets Manager, logs, S3/DynamoDB/queue/SNS/SES requests and transfer can add cost; rolling task
replacement can temporarily run two workers. There is no NAT gateway, load balancer or RDS. Free-tier credits, taxes and delayed account billing are
not modeled. Keep the exact run short-lived and destroy it. See [Fargate pricing](https://aws.amazon.com/fargate/pricing/) and
[public IPv4 pricing](https://aws.amazon.com/vpc/pricing/).

## Corrections discovered in the extension

- The framework BOM selected an older PKIX jar alongside SJM's newer Bouncy Castle family. The standalone application parent now aligns the full family,
  the supported Jakarta/Angus baseline, and the optional telemetry SDK/exporter versions. No SJM runtime workaround was added.
- JDK HTTP worker creation could lose the executable JAR's context class loader. The application HTTP boundary preserves it for provider discovery.
- Real SES setup failed in EHLO/HELO. The worker now supplies a stable SMTP client hostname through the existing SJM builder, without changing the sender,
  server endpoint, DNS or TLS verification. Subsequent simulator sends and feedback passed.
- DynamoDB Local requires an appropriate synthetic access-key shape; its local dummy credentials were corrected, not replaced with real account keys.
- OCI image indexes must be removed before referenced children. Cleanup retries those dependencies and can resume after a partial native destroy.
- Local fixtures and the AWS worker now have distinct image targets so the development TLS identity/trust anchor cannot leak into cloud deployment.
- Both application processes need distinct telemetry resource identities so cumulative metric streams do not overwrite one another.
- The AWS application now persists authoritative terminal outcomes rather than inferring receipts from thrown exceptions. Acceptance survives a later
  cleanup failure as separate SMTP evidence on a held operation. Six application-only JUnit tests cover accepted, partial, unknown, rejected and absent
  receipts, including acceptance followed by failure. The complete local AWS scenario was repeated after that correction.
- Browser review caught a missing Mermaid renderer include on strategic pages. The four extension diagrams now render as SVG, including at phone width.

## Extension reproduction and limits

Use each module README for its `up`, `scenario`, inspection and project-scoped cleanup commands. The cloud README separately documents preflight,
plan/deploy/scenario/status/destroy, profile/account fences, optional read-only network inputs and ownership-aware cleanup. Docker cleanup does not remove AWS.

Eight isolated tooling tests cover source packaging and deployment/deletion/image/history guards; they contact no cloud service. Six additional
AWS-application JUnit tests cover terminal-evidence projection; they do not contact AWS or represent a new library-wide test pass. Website verification
includes the demonstration registry, reciprocal anchors, accessible ordinary links, conditional diagram assets, a clean build and internal links.
Rendered desktop/390-pixel checks verify the hub, reference badge, reciprocal navigation and four diagrams without page overflow.

All keys, identities, tokens, documents and local feedback fixtures are synthetic. The archive AES key is intentionally public demo material. Production
deployments need trusted ingress, identity/key management, immutable document references/retention, quota decisions, monitoring and reconciliation.
No library benchmark, new library-wide reactor pass, ARM runtime validation or general AWS/server conformance claim is made by these application tests.
Raw extension logs/manifests live in ignored `.local/strategic-extension/`. Generated state, credentials, keys and build outputs are excluded from the ZIP.

## Review and teaching pass

The 2026-10-17 review added a practical entry route to every pattern, both in its README and on the website. Start here gives the matching source
checkout, exact container commands, evidence endpoint and expected result. Follow one job then names the first application method and the important
handoffs. Each chapter offers a purposeful next experiment and project-scoped cleanup. Optional cloud/Collector work stays separate from the first run.
The shared directory and website hub link straight to these entry points. Desktop and 390-pixel browser checks found no page-level horizontal overflow;
long commands scroll inside their own copyable code blocks.

The code pass separated narrative operations from SQL, protocol and infrastructure details, and explained claim, acknowledgement, protection and
resource-ownership boundaries. All application Java sources, including their tests, stay within 160 columns. Terraform formatting and CloudFormation
linting passed; comments/input descriptions did not alter infrastructure resource values or permissions. This pass provisions no additional cloud stack.
The small SES credential helper retains AWS's documented derivation, with a fixed public-example regression; no credential is written to the tests.

Corrections and focused regressions cover:

- Authoritative terminal-outcome projection in the original framework apps and CustomMailer handoff. An accepted receipt survives cleanup failure;
  an exception catch does not overwrite it. A failed evidence write leaves the existing durable guard for reconciliation.
- Failed report-to-workflow/HTTP handoff is visible, rather than hidden by successful parsing or automatically retried through duplicate input.
  The original six demos explicitly require one active instance; workflow examples explicitly react to the first correlated report, including delay.
- All owned resources are attempted during cleanup, preserving the primary exception and suppression, including short-lived evidence HTTP clients.
- SMTP fixtures require DATA's terminating dot before capture, reject oversize/interrupted uploads, clear envelope state and match tenant domains
  exactly. STARTTLS socket replacement cannot escape a concurrent drop request. They remain synthetic fixtures, not general SMTP servers.
- Scenario assertions distinguish partial acceptance from unknown acceptance, identify the current job's injected storage failure, and require a
  newly added dead letter rather than allowing old evidence to satisfy the check. Concurrent barriers compare against the current baseline.
- A real RabbitMQ startup race exposed a falsely healthy handoff receiver: Erlang ping preceded the AMQP listener, and an HTTP server survived
  failed consumer startup. Readiness now checks the actual listener/connectivity; application startup must register the consumer before publishing
  health and must roll back owned resources if initialization fails.

Source packaging and both Docker contexts exclude local keys, infrastructure state/inputs and generated files. Application-only tests remain in their
Docker build context; the published SJM reactor still gains no framework, application-test or cloud dependencies.

### Repeated verification after the review fixes

- All **14 local Docker configurations** passed again. The eight original configurations cover 32 happy/partial/unknown/delayed cases. Batch restart
  again produced two submissions before interruption and five total after resumption, with no duplicates. A separately seeded outbox `SENDING` row
  became `HELD_AFTER_RESTART` after app-only restart and repeated input produced zero SMTP submissions; this checks the guard, not a real crash window.
- Both RabbitMQ variations were rerun with the final listener/connectivity readiness checks. Custom handoff was rerun in HTTPS and RabbitMQ modes
  after the startup rollback correction; protected packages, deliberate uncertainty/rejection and dead-letter retention all passed.
- All **31 distinct application-only Java tests** passed in the Docker builds: common framework boundary 9, application support 7, Polar storage 1,
  CustomMailer producer/receiver 8 and AWS outcome projection 6. These tests add no dependency to the published library.
- All **31 isolated Python tests** passed: SMTP/scenario evidence 20, download/Docker/readiness checks 4 and cloud-deployment guards/derivation 7.
  Python compilation, Terraform format checks, CloudFormation linting and diff whitespace checks passed.
- Website verification passed **94 checks**, a clean build and **5,373 internal links across 80 pages**. Browser verification checked the hub's direct
  start link, command-first chapter layout, source route and 390-pixel containment. The source archive is regenerated from the final canonical files.
- Polar's Collector run again showed six completions and matching duration histogram counts across two distinct resource-instance identities;
  logical-send and submission spans were present. No per-email ID or recipient address was introduced as a metric label.
- Final Compose-label readback found zero containers, volumes or networks for every owned verification project. The unrelated Remark42 service
  remained running; the temporary website preview was also stopped.

Current raw logs are ignored `.local/strategic-extension/review-original-*`, `review-extended-*` and `review-website-*`. An early Custom Rabbit run
exposed the readiness bug described above; the successful final rerun replaces it as the current result, without erasing the failure log. A temporary
verification-port collision was cleaned and the remaining extension runs were executed sequentially. No real AWS resources were created or modified
during this review pass. The previous actual-cloud evidence above remains separate; this is not another hosted CI, library-wide reactor or AWS deployment pass.

## Final holistic review corrections

The 2026-10-17 final review found two more application-layer gaps, now corrected locally:

- AWS data cleanup now requires each bucket/repository's identity in the foundation inventory as well as its Project and Run tags. Both targets are
  validated before destructive cleanup starts. An interrupted inventory is recovered only from this run's Terraform state or exact captured stack;
  CloudFormation CREATE_FAILED entries cannot authorize purging a pre-existing resource. Names and matching tags alone are insufficient.
- Polar archive inspection now searches the approved bulk relay as well as the urgent/failover relays. Both ordinary and Collector scenarios verify
  byte equality, signature validity and readable protected content for bulk and urgent messages without increasing the two-charge bulk allowance.
  A repeat run caught a test assumption that enqueue order equals completion order; the regression now inspects a protected job that actually submitted.

Verification for these corrections passed 17 offline cloud-guard tests, 21 SMTP/scenario tests and four source-packaging tests: **42 isolated Python
tests**. The two rebuilt Polar configurations passed, along with their eight distinct support/storage Java tests. The final Collector run exposed
six logical completions and six duration samples across two application instances; logical-send and submission spans were present. The ordinary
configuration was repeated with the final concurrency-safe assertion too. No additional AWS infrastructure was provisioned or modified.

The source download and ownership explanations are refreshed together. Current raw evidence is ignored `.local/strategic-extension/fix-*`; earlier
failed diagnostic/test runs remain distinguishable from the successful final results. These focused checks do not replace or expand the earlier
library-wide verification, actual-cloud evidence or complete 14-configuration application matrix. All changes remain uncommitted.

## Remaining application responsibility

During #763's subsequent stronger-report-type review, the transaction-outbox application was rebuilt and its four scenarios repeated successfully.
The nine shared Java application tests passed too. Its application-owned feedback projection explicitly stores the typed status code's textual form;
no workflow or persistence responsibility moved into SJM. This is a focused compatibility rerun, not another complete portfolio/cloud pass.

The fixture is deliberately trusted and synthetic. A real receiver needs authenticated ingress, correlation/trust rules and durable duplicate/out-of-order
handling. The sample projection accepts one recipient report; SJM preserves all recipient occurrences and applications choose their own aggregation.
Feedback database commits and workflow signals/HTTP callbacks are not atomic: production systems need an application-owned outbox or another durable
handoff/reconciliation mechanism. The library does not acquire a dispatcher, persistence framework or handler registry from these examples.

## Pattern delivery check, 2026-10-23

The maintainer authorized committing the pattern work and pushing the Java and website repositories. The selection was tested in an isolated
checkout, leaving unfinished Journal articles, substantial case-study revisions, API repair changes and Angus research out of the pattern commits.
The shared Polar Meridian Mailer helper, its tests and usage notes are included because the runnable example calls that helper. The two case
studies gain only their supplementary links to the corresponding runnable patterns.

- All **14 local Docker configurations** passed on committed library baseline `9b2f42aba8a304afa8c4a4ad8d0d241ea224e06e`. The framework runs cover
  their happy, partial, unknown and delayed scenarios; batch restart again reported two submissions before interruption and five total after
  resumption, with no duplicates. Docker builds passed all **31 distinct application Java tests**.
- While that run was in progress, the independent API repairs were committed. The isolated selection was refreshed to library baseline
  `a116bf3f5d84cb7d5811c2fb3caf3d8581c6fd66` and website baseline `a8ef5a26002249e6ef2d32ae6d9f345b91096340`, preserving those commits.
  Polar Meridian, Polar with the Collector, HTTPS CustomMailer handoff and the transaction outbox were rebuilt and passed on this newer baseline.
  These builds repeat **25 distinct application Java tests**; they are a focused compatibility pass, rather than a second complete portfolio run.
- The shared helpers compile with `javac --release 11` and passed **27 Mailer checks** plus **73 dispatcher/accounting checks** on JDK 21 using
  the rebuilt application's library jars. SMTP checks use loopback fixtures; accounting uses H2 and does not establish PostgreSQL or throughput behavior.
- All **42 isolated Python tests** passed: four source-packaging/readiness checks, 21 SMTP/scenario checks and 17 offline cloud-deployment guard
  checks. Terraform format checks and CloudFormation linting passed. No AWS resources were provisioned or modified, and no external mail was sent.
- The selected website passes **105 checks**, TypeScript validation and template validation independently of unfinished Journal work. Its clean
  production build and internal-link check are repeated with the final download. The ten strategic pages offer both starting routes, use plain
  English and contain no em dashes; earlier renamed-section anchors remain available.
- The source ZIP is regenerated from the final selected application sources and canonical article helpers, including `PolarMeridianMailers.java`.
  Deployment state, local keys, credentials, Python caches and build outputs remain excluded.
- Exact project-scoped cleanup completed after every run. Final readback found **zero owned containers, volumes or networks**. The unrelated
  Remark42 service was preserved. Local build images and dependency caches are retained for reuse.

The raw delivery logs and preservation snapshots are outside the repositories in a task-specific temporary directory. This application delivery
does not claim a new library-wide reactor test pass, production TLS or partner trust validation, ARM coverage, performance results or another AWS run.
