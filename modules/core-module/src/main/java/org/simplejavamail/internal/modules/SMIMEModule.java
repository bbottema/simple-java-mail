package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.protection.ProtectionLayerResult;

import java.security.cert.X509Certificate;
import java.util.Collection;

/** Reflection-loaded optional S/MIME boundary; incoming facts are scoped to one opened envelope. */
public interface SMIMEModule {
    String NAME = "S/MIME module";

    /** Creates a private-key runtime for its owning factory; the shared entry point retains no factory runtime. */
    @NotNull SMIMEModule createRuntime(int capacity);

    /** Opens at most one envelope; the main importer owns roles, repeated layers and shared import allowances. */
    @NotNull ProtectionLayerResult openIncomingLayer(@NotNull Session session, @NotNull MimeMessage originalMessage,
            @Nullable Pkcs12Config pkcs12Config);

    @NotNull MimeMessage signMessageWithSmime(@NotNull Session session, @NotNull Email email,
            @NotNull MimeMessage messageToProtect, @NotNull SmimeSigningConfig signingConfig);

    @NotNull MimeMessage encryptMessageWithSmime(@NotNull Session session, @NotNull Email email,
            @NotNull MimeMessage messageToProtect, @NotNull SmimeEncryptionConfig encryptionConfig);

    /**
     * Encrypts one envelope for all supplied recipient certificates. Each certificate supplies one recipient block;
     * possession of any corresponding private key opens the content. Duplicate certificates are permitted.
     * Algorithm choices are shared across recipients; null uses the library default. Per-recipient certificate selection
     * is resolved by the main runtime before this operation, independently of email-wide signing configuration.
     */
    @NotNull MimeMessage encryptMessageWithSmimeForRecipients(@Nullable Session session, @NotNull Email email,
            @NotNull MimeMessage messageToProtect, @NotNull Collection<X509Certificate> recipientCerts,
            @Nullable String keyEncapsulationAlgorithm, @Nullable String cipherAlgorithm);

}
