package testutil;

import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge.Runtime;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge.CapturedContext;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge.Attempt;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge.Submission;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge.Scope;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Framework-neutral test integration: consumers test activation without depending on an optional telemetry framework. */
public final class TestMailSendTelemetry implements MailSendTelemetry {
    public final AtomicInteger bindings = new AtomicInteger();
    public final AtomicInteger sends = new AtomicInteger();
    public final AtomicInteger submissions = new AtomicInteger();
    public final List<MailSendTelemetryReport> reports = new CopyOnWriteArrayList<>();

    public Runtime bind(final boolean endpointAttributes) {
        bindings.incrementAndGet();
        return TestCapturedContext::new;
    }

    private final class TestCapturedContext implements CapturedContext {
        @Override
        public Scope makeCurrent() {
            return () -> { };
        }

        @Override
        public Attempt beginSend(final UUID observationId) {
            sends.incrementAndGet();
            return new TestAttempt();
        }
    }

    private final class TestAttempt implements Attempt {
        @Override
        public Scope makeCurrent() {
            return () -> { };
        }

        @Override
        public Submission beginSubmission(final boolean customMailer) {
            submissions.incrementAndGet();
            return new TestSubmission();
        }

        @Override
        public void complete(final MailSendTelemetryReport report) {
            reports.add(report);
        }
    }

    private static final class TestSubmission implements Submission {
        @Override
        public Scope makeCurrent() {
            return () -> { };
        }

        @Override
        public void complete(final boolean successful, final MailSubmissionStatus status, final Throwable failure,
                final String host, final Integer port) {
            // This fixture records activation and terminal reports, not provider-level facts.
        }
    }
}
