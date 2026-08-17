package org.simplejavamail.api.internal.openpgpsupport.model;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

import static java.util.Objects.requireNonNull;

/** Result of pre-parse OpenPGP/MIME recognition, verification, and decryption. */
public final class OpenPgpParseResult implements InboundProtectionResult<OriginalOpenPgpDetails> {
    private final boolean recognized;
    private final MimeMessage effectiveMimeMessage;
    private final OriginalOpenPgpDetails details;

    public OpenPgpParseResult(final boolean recognized,
                              @NotNull final MimeMessage effectiveMimeMessage,
                              @NotNull final OriginalOpenPgpDetails details) {
        this.recognized = recognized;
        this.effectiveMimeMessage = requireNonNull(effectiveMimeMessage, "effectiveMimeMessage");
        this.details = requireNonNull(details, "details");
    }

    public boolean isRecognized() { return recognized; }
    @NotNull public MimeMessage getEffectiveMimeMessage() { return effectiveMimeMessage; }
    @NotNull public OriginalOpenPgpDetails getDetails() { return details; }
}
