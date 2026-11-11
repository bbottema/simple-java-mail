package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;

/** Reflection-loaded boundary for the optional OpenPGP/MIME implementation. */
public interface OpenPgpModule {
    String NAME = "OpenPGP module";

    /** Opens at most one root envelope; the main importer owns roles, repeated layers and shared import allowances. */
    @NotNull org.simplejavamail.internal.protection.ProtectionLayerResult openIncomingLayer(@NotNull Session session,
            @NotNull MimeMessage originalMessage, @Nullable OpenPgpReceiveConfig receiveConfig);

    @NotNull MimeMessage signMessage(@NotNull Session session, @NotNull Email email,
                                     @NotNull MimeMessage message,
                                     @NotNull OpenPgpSigningConfig signingConfig);

    @NotNull MimeMessage encryptMessage(@NotNull Session session, @NotNull Email email,
                                        @NotNull MimeMessage message,
                                        @NotNull OpenPgpEncryptionConfig encryptionConfig);

}
