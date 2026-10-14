package org.simplejavamail.converter.internal;

import jakarta.mail.internet.MimeMessage;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

/** Lets conversion continue without loading the optional OpenPGP implementation when no processing took place. */
@Value
@Builder
class UnprocessedOpenPgpMail implements InboundProtectionResult<OriginalOpenPgpDetails> {
    boolean recognized;
    @NonNull MimeMessage effectiveMimeMessage;
    @NonNull OriginalOpenPgpDetails details;
}
