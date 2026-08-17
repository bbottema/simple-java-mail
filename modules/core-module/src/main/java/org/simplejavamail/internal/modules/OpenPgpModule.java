package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.internal.openpgpsupport.model.OpenPgpParseResult;

/** Reflection-loaded boundary for the optional OpenPGP/MIME implementation. */
public interface OpenPgpModule extends InboundProtectionHandler<OpenPgpReceiveConfig, org.simplejavamail.api.email.OriginalOpenPgpDetails> {
    String NAME = "OpenPGP module";

    @NotNull MimeMessage signMessage(@NotNull Session session, @NotNull Email email,
                                     @NotNull MimeMessage message,
                                     @NotNull OpenPgpSigningConfig signingConfig);

    @NotNull MimeMessage encryptMessage(@NotNull Session session, @NotNull Email email,
                                        @NotNull MimeMessage message,
                                        @NotNull OpenPgpEncryptionConfig encryptionConfig);

    @NotNull OpenPgpParseResult processIncoming(@NotNull Session session,
                                                @NotNull MimeMessage originalMessage,
                                                @Nullable OpenPgpReceiveConfig receiveConfig);
}
