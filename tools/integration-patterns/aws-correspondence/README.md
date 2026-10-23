# Protected correspondence on AWS

One containerized SJM worker uses SQS work, S3 documents/archive, DynamoDB claims/evidence, SES SMTP and SNS→SQS feedback. AWS owns infrastructure/delivery;
the application owns duplicate prevention, reconciliation and feedback interpretation. There is no workflow engine, public API or inbound SES receiver.

## Start here

Start locally, from the matching SJM checkout root. Docker with Linux containers is enough; leave the cloud account and deployment scripts aside until you have followed one job.

```text
docker compose -f tools/integration-patterns/aws-correspondence/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/aws-correspondence/compose.yaml run --rm scenario
```

Open [the local job evidence](http://localhost:8787/jobs). Expect one accepted submission per repeated job ID, readable/verified protected archive evidence, held rejected/uncertain attempts and separately recorded delivery events.

## Follow one job

Read [`CorrespondenceWorker.deliver`](src/main/java/org/simplejavamail/examples/aws/CorrespondenceWorker.java) first. It validates and conditionally claims an SQS job before `prepareArchiveAndSubmit` reads its S3 document, protects and archives it, then submits through SJM. `recordOutcome` retains the terminal SMTP facts. Follow `recordFeedback` separately: SES JSON arrives later and must not overwrite what SMTP already told us. The two paths share a job identity, not one transaction.

## Try uncertain and out-of-order results

The local scenario loses a final SMTP reply and submits duplicate/later feedback deliberately. Inspect `/jobs`: the uncertain attempt stays `HELD`; a late delay must not erase a recorded delivery. Uncorrelated feedback goes to a dead-letter queue. These are application-contract checks, not a claim that a local fixture reproduces SES.

Linux Docker/Compose is sufficient; no host JDK/Maven/account is needed. ElasticMQ, DynamoDB Local, S3Mock and TLS SMTP exercise application contracts.
SES JSON is explicitly injected. This is not AWS emulation/conformance. Fixtures are synthetic and never forward Internet mail.

The example asserts duplicate input cannot create another submission, verifies/decrypts archived content and handles duplicate/out-of-order feedback.
It also holds rejected/uncertain submissions and waits for uncorrelated feedback to reach its dead-letter queue rather than silently applying it.
The worker's inline send-result observer records submission work, not final delivery. It preserves an accepted receipt even if later cleanup fails,
while marking the operation held. If recording that result itself fails, the sticky `SENDING` guard requires reconciliation rather than replay.
SES JSON is separate from `Email.getDeliveryStatusReport()`.

## Actual cloud lane

Requirements: AWS CLI v2 with a named profile, Docker, Python 3.11+ and an isolated environment containing `cloud/requirements.txt`. The scripts use that
profile through the AWS SDK; no credentials belong in source, Terraform variables or CloudFormation parameters. Terraform itself runs in a pinned container.

Create an isolated Python virtual environment from the checkout root. Use `python3` instead of `python` here if that is how Python 3 is installed on your machine:

```text
python -m venv .local/strategic-extension/cloud-venv
```

Activate it in Windows PowerShell:

```powershell
.\.local\strategic-extension\cloud-venv\Scripts\Activate.ps1
```

Or activate it on macOS/Linux:

```sh
source .local/strategic-extension/cloud-venv/bin/activate
```

Then install the pinned tooling into that environment:

```text
python -m pip install -r tools/integration-patterns/aws-correspondence/cloud/requirements.txt
```

Use that environment's Python for the commands below. Start with `preflight`, inspect `plan`, and only then choose to provision billable resources with `deploy`.

If your shell does not allow activation, leave its security settings alone and invoke the environment's Python directly: `.local/strategic-extension/cloud-venv/Scripts/python.exe` on Windows, or `.local/strategic-extension/cloud-venv/bin/python` on macOS/Linux. Use that executable in place of `python` for installation and the cloud commands.

Both formats provision a foundation, build/push the same worker and deploy its ECR digest. Use a unique run name beginning `sjm-pattern-` (18–40 characters).
By default the run owns a small isolated network. Optionally supply `--vpc vpc-... --subnet subnet-...` (repeat `--subnet` for more) to reference an approved
network read-only. Its subnets must already support outbound access for image pulls, AWS HTTPS and SES SMTP. No route, subnet or shared security rule is
changed; this run still owns only its new task security group. Cleanup uses the recorded choices and never deletes borrowed network resources.
Supply YOUR expected account, region, existing verified SES domain and a sender underneath it. For example, replace the illustrative values below:

```text
python tools/integration-patterns/aws-correspondence/cloud/cloud.py preflight --format terraform --profile your-profile --account 123456789012 --region eu-central-1 --run sjm-pattern-example-tf-1234 --identity example.com --sender demo@example.com
```

Replace `preflight` with `plan`, `deploy`, `scenario`, `status` or `destroy`. Use `--format cloudformation` and a DIFFERENT run name for the other deployment.
`plan` does not provision. Deployment reviews an additive plan/change set; it does not import/update existing infrastructure. Manifests, state and evidence
stay under the checkout's ignored `.local/strategic-extension/aws/<run>/`. Preserve that directory until cleanup has been verified.

The existing SES identity is read-only. The example does not manage DNS, identity defaults, existing feedback subscriptions or account suppression.
It borrows the reusable comments platform's SES configuration approach, not its production resources or private environment configuration.

Created resources: a small no-ingress Fargate worker/network, ECR repository, S3 bucket, DynamoDB on-demand table, work/feedback queues with DLQs,
SNS subscription, configuration set, one-day logs and scoped IAM/Secrets Manager. Outbound public IPv4 avoids a NAT/ALB/RDS requirement but is billable.
SMTP needs a dedicated IAM user's credential; bootstrap places it in Secrets Manager outside infrastructure state. Task credentials remain separate.
Use a deployment profile authorized to create these named resources; the worker receives only its scoped task role, never that profile's credentials.

The worker explicitly sets an SMTP client hostname with `withSmtpClientHostname(...)`. This identifies the application in EHLO/HELO; it is not the SES
endpoint, sender address, DNS provisioning or a reason to relax TLS verification. Replace the synthetic hostname with the identity approved for your deployment.

The demonstration IAM policy and worker accept only SES mailbox simulator recipients. `scenario` sends synthetic protected documents for success, bounce
and complaint cases and checks real feedback plus archived object hashes/encryption. It does NOT prove a partner decrypted the message or that SMTP is
exactly-once. A persistent claim prevents blind replay after a crash/uncertain result; it can require manual reconciliation of possibly unsent work.

The cloud build explicitly targets Linux/x86_64 and excludes the local fixture's TLS private key and trust anchor. Its HTTPS/SES connections use the
image's ordinary public trust store. Only the synthetic S/MIME identity remains, to exercise protected content without using a real partner's key.

Secrets, images, compute, public IPv4, storage and service requests incur charges. Keep runs short-lived; stop/remove the exact recorded run with `destroy`.
Cleanup checks ownership before deleting its bucket objects, images, credential and infrastructure, then checks the protected identity is unchanged.
Never delete resources by account-wide glob, import production resources into this state or point both formats at the same names.
Cleanup can be resumed after an interrupted apply/destroy. It reviews Terraform deletions against the run's recorded resource IDs, handles referenced
OCI image indexes before their children, and distinguishes inactive ECS history from live infrastructure. Preserve manifests and state until it passes.
Before purging bucket contents or repository images, it checks each resource against that inventory and both ownership tags. A matching name and tags
alone never authorize deletion: a failed creation may have collided with an older deployment. Missing bookkeeping is recovered only from this run's
Terraform state or captured CloudFormation stack; failed CloudFormation creations do not establish ownership of an existing resource.

The dated shared verification record distinguishes local scenarios, template validation, actual deployments and cleanup. The synthetic S/MIME and archive
keys demonstrate mechanics only; production identity, key rotation, retention, monitoring, region/network choices and account quotas need deployment design.

## Clean up the local run

```text
docker compose -f tools/integration-patterns/aws-correspondence/compose.yaml down --volumes --remove-orphans
```

This resets only the local fixture stack. It does **not** remove real cloud resources: those require the recorded run's `destroy` command described above.
