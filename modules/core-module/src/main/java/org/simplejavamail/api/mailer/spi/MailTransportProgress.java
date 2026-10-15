package org.simplejavamail.api.mailer.spi;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailSendProgressStage;

/**
 * Lets a provider report the connection or submission work it can observe. Calling these methods records progress for Simple Java Mail;
 * it does not run the application's callback. Report as much or as little detail as your provider supports without changing how it sends.
 * <p>
 * Each sink belongs to one connection setup or submission. Keep it out of reusable message objects and stop using it when that operation ends.
 * Report actual work, not the earlier serialization used to measure SIZE or prepare replayable content. Byte counts measure serialized MIME input
 * before SMTP framing, dot escaping and TLS, rather than bytes on the wire.
 */
public interface MailTransportProgress {
    /**
     * Reports the setup or envelope exchange currently taking place. During connection setup, report greeting, capability negotiation, TLS and
     * authentication; during submission, report the sender exchange, recipient exchanges and DATA permission. When a detailed segment ends,
     * return to {@link MailSendProgressStage#ACQUIRING_CONNECTION ACQUIRING_CONNECTION} or {@link MailSendProgressStage#SUBMITTING SUBMITTING}
     * if other work continues within that operation.
     * <p>
     * Simple Java Mail ignores stages that do not belong to the current operation and notifications received after it ends. The provider cannot
     * publish FINISHED or change the submission-attempt number. Report only the stage category, never raw protocol data.
     * The default implementation does nothing, so existing sinks do not need to implement detailed reporting.
     *
     * @param stage The stage currently observed by the provider.
     */
    default void stageChanged(@NotNull MailSendProgressStage stage) { }

    /**
     * Reports the start of the actual content upload. Supply a matching input-byte total if one is already available;
     * do not serialize the message again just to calculate progress.
     *
     * @param totalBytes The known serialized-input size, or {@code null} when unavailable.
     */
    void contentTransferStarted(@Nullable Long totalBytes);

    /**
     * Updates the cumulative byte count for this submission, counting only stream writes that returned successfully.
     * If a write fails partway through, do not guess how many bytes it accepted.
     *
     * @param bytesWritten The number of content-input bytes successfully written so far.
     */
    void contentBytesWritten(long bytesWritten);

    /**
     * Reports the final content-input count once uploading finishes, before the provider completes the submission.
     * This marks the end of writing content, not SMTP acceptance.
     *
     * @param bytesWritten The final number of content-input bytes successfully written.
     */
    void contentTransferFinished(long bytesWritten);

    /**
     * Reports that the provider is about to read the final SMTP response after uploading the content.
     * Call this only if that read can actually be observed; an intermediate chunk reply is not the final response.
     */
    void waitingForResponse();
}
