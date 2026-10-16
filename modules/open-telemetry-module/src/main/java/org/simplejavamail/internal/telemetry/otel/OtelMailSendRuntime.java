package org.simplejavamail.internal.telemetry.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses only the public OpenTelemetry API. SDK processors, export and shutdown stay with the application. */
final class OtelMailSendRuntime implements MailSendTelemetryBridge.Runtime {
    private static final String INSTRUMENTATION_SCOPE = "org.simplejavamail.mail";
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("simplejavamail.send.outcome");
    private static final AttributeKey<String> SUBMISSION_STATUS = AttributeKey.stringKey("simplejavamail.submission.status");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> PHASE = AttributeKey.stringKey("simplejavamail.send.phase");
    private static final AttributeKey<String> FAILURE_PHASE = AttributeKey.stringKey("simplejavamail.send.failure.phase");
    /**
     * Use OpenTelemetry's HTTP duration scale (5 ms through 10 s), extended to five minutes for SMTP setup and rate/retry waits.
     * Generic SDK defaults begin at five whole seconds. This advice preserves sub-second detail; application Views can replace it.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-metrics/">OpenTelemetry HTTP duration recommendations</a>
     */
    private static final List<Double> DURATION_BUCKET_BOUNDARIES_SECONDS = List.of(
            0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0, 30.0, 60.0, 120.0, 300.0);

    private final Tracer tracer;
    private final boolean endpointAttributes;
    private final DoubleHistogram sendDuration;
    private final LongCounter completedSends;
    private final LongUpDownCounter activeSends;
    private final DoubleHistogram submissionDuration;
    private final LongCounter completedSubmissions;
    private final DoubleHistogram phaseDuration;

    OtelMailSendRuntime(final OpenTelemetry openTelemetry, final boolean endpointAttributes) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        this.endpointAttributes = endpointAttributes;
        final Meter meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        sendDuration = createDurationHistogram(meter, "simplejavamail.mail.send.duration",
                "Elapsed logical email-send time, including waiting and ordinary cleanup");
        completedSends = meter.counterBuilder("simplejavamail.mail.send.completed")
                .setUnit("{send}")
                .setDescription("Completed logical email sends; SMTP acceptance is not final delivery")
                .build();
        activeSends = meter.upDownCounterBuilder("simplejavamail.mail.send.active")
                .setUnit("{send}")
                .setDescription("Logical email sends that have started but have not reached their terminal boundary")
                .build();
        submissionDuration = createDurationHistogram(meter, "simplejavamail.mail.submission.duration", "Elapsed provider or CustomMailer invocation time");
        completedSubmissions = meter.counterBuilder("simplejavamail.mail.submission.completed")
                .setUnit("{submission}")
                .setDescription("Completed provider or CustomMailer invocations, including retry invocations")
                .build();
        phaseDuration = createDurationHistogram(meter, "simplejavamail.mail.send.phase.duration",
                "Available portable elapsed measurements within logical sends");
    }

    @Override
    public MailSendTelemetryBridge.CapturedContext captureContext() {
        return new CapturedTelemetryContext(Context.current());
    }

    private final class CapturedTelemetryContext implements MailSendTelemetryBridge.CapturedContext {
        private final Context parent;

        private CapturedTelemetryContext(final Context parent) {
            this.parent = parent;
        }

        @Override
        public MailSendTelemetryBridge.Scope makeCurrent() {
            return parent.makeCurrent()::close;
        }

        @Override
        public MailSendTelemetryBridge.Attempt beginSend(final UUID observationId) {
            final Span span = tracer.spanBuilder("mail.send")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(parent)
                    .setAttribute("simplejavamail.observation.id", observationId.toString())
                    .startSpan();
            try {
                final Context context = parent.with(span);
                activeSends.add(1, Attributes.empty(), context);
                return new ObservedSend(span, context);
            } catch (RuntimeException failure) {
                span.end();
                throw failure;
            }
        }
    }

    private final class ObservedSend implements MailSendTelemetryBridge.Attempt {
        private final Span span;
        private final Context context;
        private final AtomicBoolean completed = new AtomicBoolean();
        private int submissionNumber;

        private ObservedSend(final Span span, final Context context) {
            this.span = span;
            this.context = context;
        }

        @Override
        public MailSendTelemetryBridge.Scope makeCurrent() {
            return context.makeCurrent()::close;
        }

        @Override
        public MailSendTelemetryBridge.Submission beginSubmission(final boolean customMailer) {
            final Span submission = tracer.spanBuilder(customMailer ? "mail.custom_submit" : "mail.submit")
                    .setSpanKind(customMailer ? SpanKind.INTERNAL : SpanKind.CLIENT)
                    .setParent(context)
                    .setAttribute("simplejavamail.submission.attempt", ++submissionNumber)
                    .startSpan();
            return new ObservedSubmission(submission, context.with(submission));
        }

        @Override
        public void complete(final MailSendTelemetryReport report) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            final Attributes attributes = outcomeAttributes(report.isSuccessful(), report.isLoggingOnly(), report.getFailure(), report.getSubmissionStatus());
            try {
                recordSpanResult(span, report.isSuccessful(), attributes);
                if (!report.isSuccessful() && report.getFailurePhase() != null) {
                    span.setAttribute(FAILURE_PHASE, report.getFailurePhase());
                }
                // Work scopes are already closed: metric exemplars must use this send, not an unrelated current span.
                completedSends.add(1, attributes, context);
                sendDuration.record(seconds(report.getElapsed()), attributes, context);
                recordPhases(report, span, attributes, context);
            } finally {
                try {
                    activeSends.add(-1, Attributes.empty(), context);
                } finally {
                    span.end();
                }
            }
        }
    }

    private final class ObservedSubmission implements MailSendTelemetryBridge.Submission {
        private final Span span;
        private final Context context;
        private final long startedAt = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();

        private ObservedSubmission(final Span span, final Context context) {
            this.span = span;
            this.context = context;
        }

        @Override
        public MailSendTelemetryBridge.Scope makeCurrent() {
            return context.makeCurrent()::close;
        }

        @Override
        public void complete(final boolean successful, @Nullable final MailSubmissionStatus status, @Nullable final Throwable failure,
                @Nullable final String smtpHost, @Nullable final Integer smtpPort) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            final double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            final Attributes attributes = outcomeAttributes(successful, false, failure, status);
            try {
                recordSpanResult(span, successful, attributes);
                recordEndpoint(span, smtpHost, smtpPort);
                // Submission scope retirement must not move these measurements onto the logical parent span.
                completedSubmissions.add(1, attributes, context);
                submissionDuration.record(elapsed, attributes, context);
            } finally {
                span.end();
            }
        }
    }

    private static Attributes outcomeAttributes(final boolean successful, final boolean loggingOnly, @Nullable final Throwable failure,
            @Nullable final MailSubmissionStatus submissionStatus) {
        final AttributesBuilder attributes = Attributes.builder()
                .put(OUTCOME, sendOutcome(successful, loggingOnly, failure));
        if (submissionStatus != null) {
            attributes.put(SUBMISSION_STATUS, submissionStatus.name().toLowerCase(Locale.ROOT));
        }
        if (!successful) {
            attributes.put(ERROR_TYPE, failureType(failure, submissionStatus));
        }
        return attributes.build();
    }

    private static String sendOutcome(final boolean successful, final boolean loggingOnly, @Nullable final Throwable failure) {
        if (successful) {
            return loggingOnly ? "logged_only" : "success";
        }
        if (failure instanceof MailSendCancelledException) {
            return "cancelled";
        }
        if (failure instanceof MailSendTimeoutException) {
            return "timed_out";
        }
        return "failure";
    }

    private static String failureType(@Nullable final Throwable failure, @Nullable final MailSubmissionStatus status) {
        if (failure instanceof MailSendCancelledException) {
            return "cancelled";
        }
        if (failure instanceof MailSendTimeoutException) {
            return "timed_out";
        }
        return status == MailSubmissionStatus.REJECTED || status == MailSubmissionStatus.PARTIALLY_ACCEPTED ? "submission" : "operation";
    }

    private static void recordSpanResult(final Span span, final boolean successful, final Attributes attributes) {
        span.setAllAttributes(attributes);
        if (!successful) {
            span.setStatus(StatusCode.ERROR);
        }
        // Do not recordException: provider exception messages and stack traces can contain mailbox, credential or content details.
    }

    private void recordEndpoint(final Span span, @Nullable final String smtpHost, @Nullable final Integer smtpPort) {
        if (!endpointAttributes) {
            return;
        }
        if (smtpHost != null) {
            span.setAttribute("server.address", smtpHost);
        }
        if (smtpPort != null) {
            span.setAttribute("server.port", smtpPort.longValue());
        }
    }

    private void recordPhases(final MailSendTelemetryReport report, final Span span, final Attributes attributes, final Context context) {
        recordPhase("preparation", report.getPreparation(), span, attributes, context);
        recordPhase("scheduling", report.getScheduling(), span, attributes, context);
        recordPhase("rate_limit_wait", report.getRateLimitWait(), span, attributes, context);
        recordPhase("connection_acquisition", report.getConnectionAcquisition(), span, attributes, context);
        recordPhase("mime_preparation", report.getMimePreparation(), span, attributes, context);
        recordPhase("submission", report.getSubmission(), span, attributes, context);
        recordPhase("retry_wait", report.getRetryWait(), span, attributes, context);
        recordPhase("cleanup", report.getCleanup(), span, attributes, context);
    }

    private void recordPhase(final String phase, @Nullable final Duration elapsed, final Span span, final Attributes attributes, final Context context) {
        if (elapsed != null) {
            final double duration = seconds(elapsed);
            span.setAttribute("simplejavamail.send.phase." + phase + ".duration", duration);
            final Attributes phaseAttributes = attributes.toBuilder()
                    .put(PHASE, phase)
                    .build();
            phaseDuration.record(duration, phaseAttributes, context);
        }
    }

    private static DoubleHistogram createDurationHistogram(final Meter meter, final String name, final String description) {
        return meter.histogramBuilder(name)
                .setUnit("s")
                .setDescription(description)
                .setExplicitBucketBoundariesAdvice(DURATION_BUCKET_BOUNDARIES_SECONDS)
                .build();
    }

    private static double seconds(final Duration elapsed) {
        return elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
    }
}
