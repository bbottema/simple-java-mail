package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.utils.mail.dkim.Canonicalization;
import org.simplejavamail.utils.mail.dkim.DkimByteSigner;
import org.simplejavamail.utils.mail.dkim.SigningAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;

/** Adapts SJM's finalized MIME and configuration to the shared upstream DKIM engine. */
@SuppressWarnings("unused") // loaded through reflection
public final class DKIMSigner implements DKIMModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(DKIMSigner.class);
    private static final Provider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();

    @Override
    public MimeMessage signMessageWithDKIM(@NotNull final Email email,
                                           @NotNull final MimeMessage messageToSign,
                                           @NotNull final DkimConfig dkimConfig,
                                           @NotNull final Recipient fromRecipient) {
        validateHeaderExclusions(dkimConfig);
        LOGGER.debug("signing finalized MimeMessage with DKIM...");
        try {
            final FinalizedMimeMessage finalized = messageToSign instanceof FinalizedMimeMessage
                    ? (FinalizedMimeMessage) messageToSign
                    : FinalizedMimeMessage.finalizeMessage(messageToSign, FinalizedMimeMessage.ProtectionState.NONE);
            final SigningAlgorithm algorithm = dkimConfig.getSigningAlgorithm() == null
                    ? SigningAlgorithm.SHA256_WITH_RSA : SigningAlgorithm.valueOf(dkimConfig.getSigningAlgorithm());
            final String identity = fromRecipient.getAddress();
            final DkimByteSigner.Builder signer = DkimByteSigner.builder()
                    .signingDomain(dkimConfig.getDkimSigningDomain())
                    .selector(dkimConfig.getDkimSelector())
                    .privateKey(readPrivateKey(dkimConfig.getDkimPrivateKeyData(), algorithm))
                    .signingAlgorithm(algorithm)
                    .identity(identity)
                    .headerCanonicalization(canonicalization(dkimConfig.getHeaderCanonicalization()))
                    .bodyCanonicalization(canonicalization(dkimConfig.getBodyCanonicalization()))
                    .lengthParam(Boolean.TRUE.equals(dkimConfig.getUseLengthParam()))
                    .checkDomainKey(!identity.endsWith("@supersecret-testing-domain.com"));
            if (dkimConfig.getExcludedHeadersFromDkimDefaultSigningList() != null) {
                for (String excluded : dkimConfig.getExcludedHeadersFromDkimDefaultSigningList()) {
                    signer.removeHeaderToSign(excluded);
                }
            }
            final byte[] signedBytes = signer.build().sign(finalized.getSerializedBytes());
            final Session session = finalized.getSession() != null
                    ? finalized.getSession()
                    : Session.getInstance(new Properties());
            return FinalizedMimeMessage.fromMessageBytes(session, signedBytes,
                    FinalizedMimeMessage.ProtectionState.FINAL_WIRE_SIGNED);
        } catch (Exception e) {
            throw new DKIMSigningException(DKIMSigningException.ERROR_SIGNING_DKIM_INVALID_DOMAINKEY, e);
        }
    }

    private static void validateHeaderExclusions(final DkimConfig config) {
        final Set<String> exclusions = config.getExcludedHeadersFromDkimDefaultSigningList();
        if (exclusions != null && exclusions.stream().anyMatch(header -> header != null && "From".equalsIgnoreCase(header.trim()))) {
            throw new IllegalArgumentException("DKIM signatures must include the From header; it cannot be excluded from the default signing list");
        }
    }

    private static PrivateKey readPrivateKey(final byte[] configuredBytes, final SigningAlgorithm algorithm) throws Exception {
        byte[] keyBytes = configuredBytes;
        final String possiblePem = new String(configuredBytes, StandardCharsets.US_ASCII);
        if (possiblePem.contains("-----BEGIN")) {
            keyBytes = Base64.getMimeDecoder().decode(possiblePem
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", ""));
        }
        final KeyFactory keyFactory = algorithm == SigningAlgorithm.SHA256_WITH_ED25519
                ? KeyFactory.getInstance("Ed25519", BOUNCY_CASTLE_PROVIDER)
                : KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static Canonicalization canonicalization(final DkimConfig.Canonicalization configured) {
        return configured == DkimConfig.Canonicalization.SIMPLE ? Canonicalization.SIMPLE : Canonicalization.RELAXED;
    }
}
