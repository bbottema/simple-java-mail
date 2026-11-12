Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# SOCKS authentication corrections

The maintainer approved design, execution and full review-workflow delivery for
[#901](https://github.com/bbottema/simple-java-mail/issues/901) and
[#902](https://github.com/bbottema/simple-java-mail/issues/902) on 29 October 2026.
[ADR 0010](../adr/0010-authenticated-socks-bridge.md) owns the bridge contract.

The authentication worker owns `SocksAuthenticationHelper`, its focused logging and
reply regressions, and the ADR 0010 clarification for these two corrections. The lead
owns shared release history, the ADR index, website pages, OpenRewrite documents and
cleanup bookkeeping, and accepts the combined change before serialized delivery.

The accepted behavior keeps proxy credentials on the wire and out of logging. The
helper must consume both authentication reply bytes, accept only subnegotiation
version 1, honor the real status, fail on truncated input and leave the next exchange
unread. Public configuration and bridge resource ownership remain unchanged.

Use focused red-first regressions and adjacent module tests in the task-owned
`.local/feature-review-auth-20261029/snapshot`, then exercise real JDK 11 and a modern
JDK. This snapshot and its source archive are disposable. The lead receives actual
results directly; GitHub issues and commits contain behavior and decisions only.

Check existing proxy/debugging guidance, Journal and case studies for affected claims.
No API signatures, property keys, Spring metadata or CLI generation change. Wider
SOCKS scrutiny is separate; the tunnel timeout and remote setup ownership findings
are not authorized repairs in this packet.
