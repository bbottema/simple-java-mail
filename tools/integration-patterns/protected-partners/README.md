# Exchange protected partner documents

Coordinate partner work while keeping protection, inspection and byte preservation at the mail boundary.

A Camel route submits a signed message through SJM, fetches the local fixture's captured bytes, verifies them through the existing Email converter and archives those captured bytes without rebuilding MIME.

Camel owns routing and the application owns its archive. SJM owns S/MIME and MIME interpretation. The locally generated identity demonstrates signature integrity, not a trusted partner identity or a production PKI.

## Start here

Run from the matching SJM checkout root with Docker's Linux containers. The build creates a synthetic signing identity; you do not need keys, a host JDK or a real mail account.

```text
docker compose -f tools/integration-patterns/protected-partners/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/protected-partners/compose.yaml run --rm scenario
```

Inspect [the submitted jobs](http://localhost:8787/jobs) and the `PROTECTED_ARCHIVED` application event checked by the scenario. That event means the captured bytes were verified and archived, not that an Internet partner trusted the certificate.

## Follow one job

Read [`PartnerDocuments`](src/main/java/org/simplejavamail/examples/patterns/PartnerDocuments.java) first. Its Camel route delegates the send to [`MailWork.deliver`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java), then `archiveVerifiedCopy` fetches the fixture's actual MIME, verifies it through SJM and writes those same bytes. The original input and archived representation have different roles: do not rebuild the archive from parsed getters.

## Try an unsafe result

Run `docker compose -f tools/integration-patterns/protected-partners/compose.yaml run --rm scenario unknown`. A missing final SMTP reply produces held submission evidence. Verification and archive work cannot turn that uncertainty into a known delivery result or permission to resend.

The signing key is generated inside the build and valid for thirty days. It is a synthetic identity with a plainly documented demo password. Replace the whole local deployment model, not just that password, before considering production use.

Like the other original framework examples, this is a single application instance, not a distributed recovery design. [The shared notes](../README.md) explain that limit, resources and licensing. Cleanup removes only its synthetic data:

```text
docker compose -f tools/integration-patterns/protected-partners/compose.yaml down --volumes --remove-orphans
```
