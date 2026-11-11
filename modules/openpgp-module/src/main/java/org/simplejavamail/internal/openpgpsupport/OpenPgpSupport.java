package org.simplejavamail.internal.openpgpsupport;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.openpgp.PGPException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pgpainless.exception.KeyException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.internal.modules.OpenPgpModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** RFC 3156 OpenPGP/MIME implementation backed by PGPainless. */
public final class OpenPgpSupport implements OpenPgpModule {

    private final PgpainlessOpenPgpAdapter pgpainlessAdapter = new PgpainlessOpenPgpAdapter();

    @NotNull
    @Override
    public org.simplejavamail.internal.protection.ProtectionLayerResult openIncomingLayer(@NotNull final Session session,
            @NotNull final MimeMessage originalMessage, @Nullable final OpenPgpReceiveConfig receiveConfig) {
        return new OpenPgpIncomingLayer(pgpainlessAdapter).open(session, originalMessage, receiveConfig);
    }

    private static final class PreparationFailure extends org.simplejavamail.MailException {
        private static final long serialVersionUID = 1L;
        private PreparationFailure(final String message, final Throwable cause) {
            super(MailFailureKind.PREPARATION, message, cause, null);
        }
    }

    @NotNull
    @Override
    public MimeMessage signMessage(@NotNull final Session session, @NotNull final Email email,
            @NotNull final MimeMessage message, @NotNull final OpenPgpSigningConfig signingConfig) {
        try {
            final byte[] serializedMessage = MimeEntitySupport.serialize(message);
            final byte[] signedEntity = MimeEntitySupport.normalizeCrlf(
                    MimeEntitySupport.contentEntity(serializedMessage));
            requireSevenBit(signedEntity);
            final byte[] signature = pgpainlessAdapter.createDetachedSignature(signedEntity, signingConfig);
            final byte[] protectedMessage = MimeEntitySupport.wrapSigned(serializedMessage, signature, boundary("signed"),
                    "pgp-" + signingConfig.getHashAlgorithm().name().toLowerCase(Locale.ROOT));
            return FinalizedMimeMessage.fromMessageBytes(session, protectedMessage,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException | KeyException e) {
            throw new PreparationFailure("Unable to sign OpenPGP/MIME message", e);
        }
    }

    @NotNull
    @Override
    public MimeMessage encryptMessage(@NotNull final Session session, @NotNull final Email email,
            @NotNull final MimeMessage message, @NotNull final OpenPgpEncryptionConfig encryptionConfig) {
        try {
            final byte[] serializedMessage = MimeEntitySupport.serialize(message);
            final byte[] encryptedEntity = pgpainlessAdapter.encrypt(
                    MimeEntitySupport.contentEntity(serializedMessage), encryptionConfig);
            final byte[] protectedMessage = MimeEntitySupport.wrapEncrypted(
                    serializedMessage, encryptedEntity, boundary("encrypted"));
            return FinalizedMimeMessage.fromMessageBytes(session, protectedMessage,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException | KeyException e) {
            throw new PreparationFailure("Unable to encrypt OpenPGP/MIME message", e);
        }
    }

    private static String boundary(final String type) {
        return "----=_SimpleJavaMail_OpenPGP_" + type + '_' + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireSevenBit(final byte[] signedEntity) {
        for (byte value : signedEntity) {
            if ((value & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "OpenPGP/MIME signed entities must use a 7-bit-safe Content-Transfer-Encoding");
            }
        }
    }
}
