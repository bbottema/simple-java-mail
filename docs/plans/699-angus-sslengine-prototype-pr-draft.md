# [Draft] Explore SMTP PIPELINING with SSLEngine and nonblocking I/O

Related to #213 and the reader-thread draft. This is a runnable experiment, not
an integrated replacement for Angus's TLS transport. It adds test code, a fixture
and running instructions; production code and dependencies are unchanged.

The reader-thread implementation can stall when TLS 1.3 processing needs to
write a key update while both peers are blocked writing. This alternative uses
Java's existing SSLEngine with a nonblocking channel, so incoming replies can
still be processed while output is pending.

One calling thread owns the connection and SMTP state. There is no separate SMTP
reader thread to coordinate or join. Delegated TLS tasks use a worker. This
would be a larger change to Angus's I/O handling, but a simpler SMTP concurrency
model, not a new TLS or cryptographic implementation.

## Results and reproduction

In the original matched pressure tests on JDK 11, 17 and 21, the engine completed
18/18 trials, the reader-thread implementation completed 9/18, and sequential
sending completed 18/18. The engine continued processing replies during blocked
writes. Including fragmented-I/O cases, it delivered 40 messages on 20 reused
connections without reconnecting. Ten failure checks behaved as expected,
including timeout, disconnect, unacknowledged content and certificate/hostname
rejection. These are deliberately demanding test conditions, not production
failure-rate estimates or an additional performance benchmark.

The packaged runner also passes on JDK 11/17/21. All six optional reader
comparisons completed in the latest run; reproducing the stall depends on timing.

The optional JUnit entry point includes STARTTLS and implicit TLS, frequent key
updates, restricted buffers and complete, valid SMTP commands:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPSSLEngineTest -Dsurefire.failIfNoSpecifiedTests=false -Dangus.sslengine.prototype=true package
```

Each case runs in a bounded child JVM. The fixture README explains how to include
the reader-thread comparison and where to find the output.

## Direction to decide

The experiment does not yet preserve Angus's full Transport contract, including
subclass hooks, events, partial recipients, BDAT, cancellation, proxies and
custom socket factories. Avoiding the reproduced stall is not proof that every
possible exchange will progress.

I propose deciding whether this broader I/O change is worth pursuing before
integrating it. TLS Channel and Netty are also possible reuse options if an
additional dependency is acceptable; neither has been tested against this
reproducer. The current experiment needs neither.

Developed with AI assistance; implementation and test results reviewed locally.
