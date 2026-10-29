package testutil.retry;

import jakarta.mail.MessagingException;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.List;
import java.io.ByteArrayOutputStream;

/** Demonstrates the single repeatable-content hook and structured retry evidence from an independent provider. */
public final class RetryProviderAdapter implements MailTransportAdapter {
    @Override public boolean supports(final Transport transport) { return transport instanceof RetryProviderTransport; }
    @Override public boolean supportsContentRequirement(final ContentRequirement requirement) { return true; }

    @Override public PreparedMail prepareForRepeatedSubmission(final Transport transport, final PreparedMail original, final MimeMessage frozen)
            throws MessagingException {
        final RetryProviderTransport.State state = ((RetryProviderTransport) transport).state;
        if (!state.repeatable) { return MailTransportAdapter.super.prepareForRepeatedSubmission(transport, original, frozen); }
        state.captures.incrementAndGet();
        return PreparedMail.builder()
            .withMimeMessage(frozen)
            .withRecipients(original.getRecipients())
            .withDeliveryEnvelope(original.getDeliveryEnvelope())
            .withContentRequirement(original.getContentRequirement())
            .withLegacySmtpContentSupportEnabled(original.isLegacySmtpContentSupportEnabled())
            .build();
    }

    @Override public MailTransportResult sendMessage(final Transport transport, final PreparedMail prepared) {
        final RetryProviderTransport.State state = ((RetryProviderTransport) transport).state;
        final int invocation = state.invocations.incrementAndGet();
        recordSubmittedBytes(state, prepared.getMimeMessage());
        if (invocation > 1 && state.laterInvocationFailure != null) {
            if (state.laterInvocationFailure instanceof RuntimeException) {
                throw (RuntimeException) state.laterInvocationFailure;
            }
            throw (Error) state.laterInvocationFailure;
        }
        final SendFailedException failure = new SendFailedException("structured fixture refusal", null, null, prepared.getRecipients(), null);
        final SmtpServerResponse temporary = new SmtpServerResponse(451, "temporary");
        if (state.uncertain) { return MailTransportResult.failedWithUnknownAcceptance(failure, null, null); }
        if (state.partial) {
            return MailTransportResult.failed(failure, temporary, new Address[] { prepared.getRecipients()[0] },
                    new Address[] { prepared.getRecipients()[1] }, null);
        }
        if (state.permanent) { return MailTransportResult.failed(failure, new SmtpServerResponse(550, "permanent")); }
        if (invocation <= state.temporaryRefusals) {
            return MailTransportResult.failed(failure, temporary).withRecipientResults(List.of(new MailRecipientResult(
                    prepared.getRecipients()[0].toString(), prepared.getRecipients()[0].toString(), MailRecipientDisposition.VALID_UNSENT, true, temporary)),
                    MailRetryDisposition.SAFE_TO_RETRY_ALL);
        }
        return MailTransportResult.accepted(prepared.getRecipients(), new SmtpServerResponse(250, "accepted"));
    }

    private static void recordSubmittedBytes(final RetryProviderTransport.State state, final MimeMessage message) {
        try {
            final ByteArrayOutputStream content = new ByteArrayOutputStream();
            message.writeTo(content);
            state.content.add(content.toByteArray());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
