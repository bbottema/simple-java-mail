package org.simplejavamail.api.internal.openpgpsupport.model;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

/** Result of pre-parse OpenPGP/MIME recognition, verification, and decryption. */
@ApiStatus.Internal
public interface OpenPgpParseResult extends InboundProtectionResult<OriginalOpenPgpDetails> {

	boolean isRecognized();

	@NotNull MimeMessage getEffectiveMimeMessage();

	@NotNull OriginalOpenPgpDetails getDetails();
}
