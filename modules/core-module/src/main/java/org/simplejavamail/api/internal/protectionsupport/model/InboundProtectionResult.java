package org.simplejavamail.api.internal.protectionsupport.model;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;

/** Provider-neutral result passed from a security handler to the ordinary MIME parser. */
public interface InboundProtectionResult<D> {
    boolean isRecognized();
    @NotNull MimeMessage getEffectiveMimeMessage();
    @NotNull D getDetails();
}
