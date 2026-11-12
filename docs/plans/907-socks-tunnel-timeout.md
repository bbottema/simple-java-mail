Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# Transparent established SOCKS tunnels

The maintainer approved design, execution and full review-workflow delivery for
[#907](https://github.com/bbottema/simple-java-mail/issues/907) on 29 October 2026.
[ADR 0010](../adr/0010-authenticated-socks-bridge.md) owns the bridge lifetime contract.

The accepted change retains the local SOCKS handshake guard and removes its read
timeout when the accepted socket becomes a forwarding tunnel. Established forwarding
has no independent read or idle timeout; Angus enforces the SMTP client's configured
read timeout. Listener shutdown, pooled connections, public configuration and pending
remote setup ownership remain outside this correction.

The worker owns the narrow `Socks5Handler` transition, focused handler and public
SMTP/SOCKS regressions, and ADR 0010 clarification. The lead owns shared release
history, website guidance, ADR index, OpenRewrite documents and cleanup bookkeeping,
and reviews the combined diff before serialized publication.

Use a deterministic timeout-socket boundary and one real response delayed beyond ten
seconds, retaining a longer client timeout and subsequent pooled reuse. A shorter
client timeout must still fail and clean up. Exercise neighboring retained/pooling
controls and real JDK 11 plus a modern JDK in the task-owned
`.local/feature-review-tunnel-timeout-20261029/snapshot`. The scratch directory and
source archive are disposable; report execution evidence directly to the maintainer.

No API signatures, configuration defaults, property keys, Spring metadata or CLI
generation change. Keep the separate pending-setup and wider SOCKS review findings
unimplemented. Retire this active plan after accepted delivery.
