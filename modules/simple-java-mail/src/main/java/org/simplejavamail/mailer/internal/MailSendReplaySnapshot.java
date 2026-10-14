package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** One private immutable representation and its byte permit; never stored on the reusable Email, Session or pooled transport. */
final class MailSendReplaySnapshot implements AutoCloseable {
    @Nullable private PreparedMail prepared;
    private final MailSendReplayBudget.Reservation reservation;

    private MailSendReplaySnapshot(final PreparedMail prepared, final MailSendReplayBudget.Reservation reservation) {
        this.prepared = prepared;
        this.reservation = reservation;
    }

    static MailSendReplaySnapshot capture(final Transport transport, final PreparedMail original, final MailSendReplayBudget budget,
            final MailSendControl control) throws MessagingException {
        final MailSendReplayBudget.Reservation reservation = budget.reserve();
        try {
            final MimeMessage frozen = freezeContent(original, reservation, control);
            final PreparedMail repeated = TransportRunner.prepareForRepeatedSubmission(transport, original, frozen);
            control.checkStopped();
            return new MailSendReplaySnapshot(repeated, reservation);
        } catch (MessagingException | RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    private static MimeMessage freezeContent(final PreparedMail original, final MailSendReplayBudget.Reservation reservation,
            final MailSendControl control) throws MessagingException {
        control.checkStopped();
        final MimeMessage source = original.getMimeMessage();
        if (source instanceof FinalizedMimeMessage) {
            // Exact/protected content already owns stable bytes. Count that representation without serializing or copying it again.
            reservation.retain(((FinalizedMimeMessage) source).getSerializedSize());
            return source;
        }
        final byte[] captured = captureContent(source, reservation, control);
        return FinalizedMimeMessage.fromOwnedBytes(source.getSession(), captured, FinalizedMimeMessage.ProtectionState.NONE,
                original.getContentRequirement() == ContentRequirement.PRESERVE_ALL_BYTES);
    }

    private static byte[] captureContent(final MimeMessage source, final MailSendReplayBudget.Reservation reservation,
            final MailSendControl control) throws MessagingException {
        final ByteArrayOutputStream captured = boundedCaptureBuffer(reservation, control);
        try {
            // writeTo finalizes ordinary MIME once; calling saveChanges again would regenerate its Message-ID.
            source.writeTo(captured);
        } catch (IOException failure) {
            throw new MessagingException("Couldn't capture unchanged content for automatic retries. No message was submitted.", failure);
        }
        return captured.toByteArray();
    }

    private static ByteArrayOutputStream boundedCaptureBuffer(final MailSendReplayBudget.Reservation reservation, final MailSendControl control) {
        // Charge each write before retaining it: reject oversized capture without reading the rest of a large attachment first.
        // ByteArrayOutputStream growth and the final array copy are temporary heap costs, not additional retained-content charges.
        return new ByteArrayOutputStream() {
            @Override public synchronized void write(final byte[] bytes, final int offset, final int length) {
                control.checkStopped();
                reservation.retain(length);
                super.write(bytes, offset, length);
            }
            @Override public synchronized void write(final int value) {
                control.checkStopped();
                reservation.retain(1);
                super.write(value);
            }
        };
    }

    PreparedMail prepared() {
        if (prepared == null) {
            throw new IllegalStateException("This email's replay snapshot has already been released.");
        }
        return prepared;
    }

    @Override public void close() {
        prepared = null;
        reservation.close();
    }
}
