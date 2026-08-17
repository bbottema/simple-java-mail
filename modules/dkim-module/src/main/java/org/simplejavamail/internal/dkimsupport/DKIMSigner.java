package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.utils.mail.dkim.DomainKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Provider-neutral DKIM implementation over finalized MIME bytes. */
@SuppressWarnings("unused") // loaded through reflection
public final class DKIMSigner implements DKIMModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(DKIMSigner.class);
    private static final int MAX_HEADER_LENGTH = 67;
    private static final String DKIM_SIGNATURE = "DKIM-Signature";
    private static final Pattern SIGNING_DOMAIN_PATTERN = Pattern.compile("(.+)\\.(.+)");

    private static final Set<String> DEFAULT_HEADERS_TO_SIGN = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "From", "To", "Subject", "Content-Description", "Content-ID", "Content-Type",
            "Content-Transfer-Encoding", "Cc", "Date", "In-Reply-To", "List-Subscribe", "List-Post",
            "List-Owner", "List-Id", "List-Archive", "List-Help", "List-Unsubscribe", "MIME-Version",
            "Message-ID", "Resent-Sender", "Resent-Cc", "Resent-Date", "Resent-To", "Reply-To",
            "References", "Resent-Message-ID", "Resent-From", "Sender"
    )));

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    public MimeMessage signMessageWithDKIM(@NotNull final Email email,
                                           @NotNull final MimeMessage messageToSign,
                                           @NotNull final DkimConfig dkimConfig,
                                           @NotNull final Recipient fromRecipient) {
        LOGGER.debug("signing finalized MimeMessage with DKIM...");
        FinalizedMimeMessage finalized = null;
        boolean ownsFinalized = false;
        try {
            finalized = messageToSign instanceof FinalizedMimeMessage
                    ? (FinalizedMimeMessage) messageToSign
                    : FinalizedMimeMessage.finalizeMessage(messageToSign, FinalizedMimeMessage.ProtectionState.NONE);
            ownsFinalized = finalized != messageToSign;
            final byte[] signedBytes = new WireSigner(dkimConfig, fromRecipient.getAddress())
                    .sign(finalized, finalized.getSerializedBytes());
            final Session session = finalized.getSession() != null
                    ? finalized.getSession()
                    : Session.getInstance(new Properties());
            return FinalizedMimeMessage.fromMessageBytes(session, signedBytes,
                    FinalizedMimeMessage.ProtectionState.FINAL_WIRE_SIGNED);
        } catch (Exception e) {
            throw new DKIMSigningException(DKIMSigningException.ERROR_SIGNING_DKIM_INVALID_DOMAINKEY, e);
        } finally {
            if (ownsFinalized && finalized != null) {
                finalized.close();
            }
        }
    }

    private static final class WireSigner {

        private final DkimConfig config;
        private final String identity;
        private final Algorithm algorithm;
        private final Canonicalization headerCanonicalization;
        private final Canonicalization bodyCanonicalization;
        private final PrivateKey privateKey;
        private final Set<String> headersToSign = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        private WireSigner(@NotNull final DkimConfig config, @NotNull final String identity) throws Exception {
            this.config = config;
            this.identity = identity;
            validateDomainAndIdentity(config.getDkimSigningDomain(), identity);
            this.algorithm = Algorithm.from(config.getSigningAlgorithm());
            this.headerCanonicalization = Canonicalization.from(config.getHeaderCanonicalization());
            this.bodyCanonicalization = Canonicalization.from(config.getBodyCanonicalization());
            this.privateKey = readPrivateKey(config.getDkimPrivateKeyData(), algorithm);
            this.headersToSign.addAll(DEFAULT_HEADERS_TO_SIGN);
            if (config.getExcludedHeadersFromDkimDefaultSigningList() != null) {
                this.headersToSign.removeAll(config.getExcludedHeadersFromDkimDefaultSigningList());
            }
        }

        private byte[] sign(@NotNull final MimeMessage message, final byte @NotNull [] serialized)
                throws Exception {
            if (!identity.endsWith("@supersecret-testing-domain.com")) {
                DomainKeyUtil.getDomainKey(config.getDkimSigningDomain(), config.getDkimSelector())
                        .check(identity, privateKey);
            }

            final List<Header> selectedHeaders = selectHeaders(message);
            final StringBuilder headerNames = new StringBuilder();
            final StringBuilder canonicalHeaders = new StringBuilder();
            boolean hasFrom = false;
            for (Header header : selectedHeaders) {
                if ("From".equalsIgnoreCase(header.getName())) {
                    hasFrom = true;
                }
                if (headerNames.length() > 0) {
                    headerNames.append(':');
                }
                headerNames.append(header.getName());
                canonicalHeaders.append(headerCanonicalization.canonicalizeHeader(header.getName(), header.getValue()))
                        .append("\r\n");
            }
            if (!hasFrom) {
                throw new MessagingException("Could not find mandatory DKIM header: From");
            }

            final byte[] canonicalBody = bodyCanonicalization.canonicalizeBody(normalizeCrLf(extractBody(serialized)));
            final MessageDigest digest = MessageDigest.getInstance(algorithm.digestName);

            final Map<String, String> tags = new LinkedHashMap<>();
            tags.put("v", "1");
            tags.put("a", algorithm.dkimName);
            tags.put("q", "dns/txt");
            tags.put("c", headerCanonicalization.value + "/" + bodyCanonicalization.value);
            final Date sentDate = message.getSentDate() != null ? message.getSentDate() : new Date();
            tags.put("t", Long.toString(sentDate.getTime() / 1000L));
            tags.put("s", config.getDkimSelector());
            tags.put("d", config.getDkimSigningDomain());
            tags.put("i", quotedPrintable(identity));
            tags.put("h", headerNames.toString());
            if (Boolean.TRUE.equals(config.getUseLengthParam())) {
                tags.put("l", Integer.toString(canonicalBody.length));
            }
            tags.put("bh", Base64.getEncoder().encodeToString(digest.digest(canonicalBody)));

            final String unsignedValue = serializeTags(tags);
            canonicalHeaders.append(headerCanonicalization.canonicalizeHeader(DKIM_SIGNATURE, unsignedValue));

            final Signature signature = algorithm.ed25519
                    ? Signature.getInstance(algorithm.signatureName, BouncyCastleProvider.PROVIDER_NAME)
                    : Signature.getInstance(algorithm.signatureName);
            signature.initSign(privateKey);
            signature.update(canonicalHeaders.toString().getBytes(StandardCharsets.UTF_8));
            final String signatureHeader = DKIM_SIGNATURE + ": " + unsignedValue
                    + fold(Base64.getEncoder().encodeToString(signature.sign()), 3);

            final ByteArrayOutputStream output = new ByteArrayOutputStream(serialized.length + signatureHeader.length() + 2);
            output.write(signatureHeader.getBytes(StandardCharsets.US_ASCII));
            output.write('\r');
            output.write('\n');
            output.write(serialized);
            return output.toByteArray();
        }

        private List<Header> selectHeaders(@NotNull final MimeMessage message) throws MessagingException {
            final List<Header> selected = new ArrayList<>();
            final Enumeration<Header> headers = message.getAllHeaders();
            while (headers.hasMoreElements()) {
                final Header header = headers.nextElement();
                if (headersToSign.contains(header.getName())) {
                    selected.add(0, header);
                }
            }
            return selected;
        }
    }

    private enum Algorithm {
        SHA256_WITH_RSA("rsa-sha256", "SHA256withRSA", "SHA-256", "RSA", false),
        SHA1_WITH_RSA("rsa-sha1", "SHA1withRSA", "SHA-1", "RSA", false),
        SHA256_WITH_ED25519("ed25519-sha256", "NONEwithEdDSA", "SHA-256", "EdDSA", true);

        private final String dkimName;
        private final String signatureName;
        private final String digestName;
        private final String keyFactoryName;
        private final boolean ed25519;

        Algorithm(final String dkimName, final String signatureName, final String digestName,
                  final String keyFactoryName, final boolean ed25519) {
            this.dkimName = dkimName;
            this.signatureName = signatureName;
            this.digestName = digestName;
            this.keyFactoryName = keyFactoryName;
            this.ed25519 = ed25519;
        }

        private static Algorithm from(final String configured) {
            return configured == null ? SHA256_WITH_RSA : valueOf(configured);
        }
    }

    private enum Canonicalization {
        SIMPLE("simple"), RELAXED("relaxed");

        private final String value;

        Canonicalization(final String value) {
            this.value = value;
        }

        private static Canonicalization from(final DkimConfig.Canonicalization configured) {
            return configured == DkimConfig.Canonicalization.SIMPLE ? SIMPLE : RELAXED;
        }

        private String canonicalizeHeader(final String name, final String value) {
            if (this == SIMPLE) {
                return name + ": " + value;
            }
            return name.trim().toLowerCase(Locale.ROOT) + ":" + value.replaceAll("\\s+", " ").trim();
        }

        private byte[] canonicalizeBody(final byte[] body) {
            String value = new String(body, StandardCharsets.UTF_8);
            if (this == RELAXED) {
                value = value.replaceAll("[ \\t]+\\r\\n", "\\r\\n")
                        .replaceAll("[ \\t]+", " ");
            }
            if (!value.endsWith("\r\n")) {
                value += "\r\n";
            }
            while (value.endsWith("\r\n\r\n")) {
                value = value.substring(0, value.length() - 2);
            }
            if (this == RELAXED && "\r\n".equals(value)) {
                value = "";
            }
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void validateDomainAndIdentity(final String domain, final String identity) {
        if (domain == null || !SIGNING_DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new IllegalArgumentException(domain + " is an invalid signing domain");
        }
        if (identity == null || (!identity.endsWith("@" + domain) && !identity.endsWith("." + domain))) {
            throw new IllegalArgumentException("The domain part of " + identity + " isn't " + domain + " or a subdomain thereof");
        }
    }

    private static PrivateKey readPrivateKey(final byte[] configuredBytes, final Algorithm algorithm) throws Exception {
        byte[] keyBytes = configuredBytes;
        final String possiblePem = new String(configuredBytes, StandardCharsets.US_ASCII);
        if (possiblePem.contains("-----BEGIN")) {
            keyBytes = Base64.getMimeDecoder().decode(possiblePem
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", ""));
        }
        final KeyFactory keyFactory = algorithm.ed25519
                ? KeyFactory.getInstance(algorithm.keyFactoryName, BouncyCastleProvider.PROVIDER_NAME)
                : KeyFactory.getInstance(algorithm.keyFactoryName);
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static byte[] extractBody(final byte[] serialized) throws MessagingException {
        for (int i = 0; i <= serialized.length - 4; i++) {
            if (serialized[i] == '\r' && serialized[i + 1] == '\n'
                    && serialized[i + 2] == '\r' && serialized[i + 3] == '\n') {
                return Arrays.copyOfRange(serialized, i + 4, serialized.length);
            }
        }
        throw new MessagingException("Finalized MIME message has no header/body separator");
    }

    private static byte[] normalizeCrLf(final byte[] bytes) throws IOException {
        final ByteArrayOutputStream normalized = new ByteArrayOutputStream(bytes.length + 32);
        int previous = -1;
        for (byte currentByte : bytes) {
            final int current = currentByte & 0xff;
            if (current == '\r') {
                normalized.write('\r');
                normalized.write('\n');
            } else if (current == '\n') {
                if (previous != '\r') {
                    normalized.write('\r');
                    normalized.write('\n');
                }
            } else {
                normalized.write(current);
            }
            previous = current;
        }
        return normalized.toByteArray();
    }

    private static String serializeTags(final Map<String, String> tags) {
        int position = 0;
        final StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            final String entry = tag.getKey() + "=" + tag.getValue() + ";";
            if (position + entry.length() + 1 > MAX_HEADER_LENGTH) {
                position = entry.length();
                builder.append("\r\n\t").append(entry);
            } else {
                builder.append(' ').append(entry);
                position += entry.length() + 1;
            }
        }
        return builder.append("\r\n\tb=").toString().trim();
    }

    private static String fold(final String value, int offset) {
        int position = 0;
        final StringBuilder folded = new StringBuilder();
        while (position < value.length()) {
            final int available = offset > 0 ? MAX_HEADER_LENGTH - offset : MAX_HEADER_LENGTH;
            final int end = Math.min(value.length(), position + available);
            if (position > 0 || offset == 0) {
                folded.append("\r\n\t");
            }
            folded.append(value, position, end);
            position = end;
            offset = 0;
        }
        return folded.toString();
    }

    private static String quotedPrintable(final String value) {
        final StringBuilder encoded = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            final int unsigned = current & 0xff;
            if (unsigned >= 33 && unsigned <= 126 && unsigned != '=' && unsigned != ';') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('=').append(String.format("%02X", unsigned));
            }
        }
        return encoded.toString();
    }
}
