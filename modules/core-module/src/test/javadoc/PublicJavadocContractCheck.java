import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the published HTML, not Lombok's compiled accessors or an older test-phase report. */
public final class PublicJavadocContractCheck {
    private final Path documentation;

    private PublicJavadocContractCheck(final Path documentation) {
        this.documentation = documentation;
    }

    public static void main(final String[] arguments) throws IOException {
        new PublicJavadocContractCheck(Path.of(arguments[0])).verifyPublishedContracts();
    }

    private void verifyPublishedContracts() throws IOException {
        requireGetters("email/Recipient", "getName", "getAddress", "getType", "getSmimeCertificate",
                "getDeliveryStatusNotificationNotifyOptions");
        requireGetters("email/Recipients", "getRecipients", "getDefaultName", "getOverridingName");
        requireGetters("email/ContentTransferEncoding", "getEncoder");
        requireGetters("mailer/MailSendRejectedException", "getReason");
        requireGetters("mailer/MailRecipientResult", "getOriginalAddress", "getDisposition");
        requireGetters("mailer/SmtpTlsDetails", "getProtocol", "getCipherSuite", "getPeerIdentities", "isServerIdentityCheckEnabled");
        requireGetters("mailer/spi/DeliveryRecipient", "getAddress", "getNotifyOptions");
        requireGetters("mailer/config/OperationalConfig", "getSessionTimeout");
        requireGetters("email/IRecipientsBuilder", "resetSmimeCertificatePolicy", "resetDeliveryStatusNotificationNotifyPolicy");
        requireText("mailer/MailerGenericBuilder", "a caller-supplied Session");
        requireText("email/Recipient", "IRecipientBuilder.html#withName(java.lang.String)");
        requireText("email/Recipients", "IRecipientsBuilder.html#withRecipient(org.simplejavamail.api.email.Recipient)");
        requireLinkedCompanions("mailer/config/FailoverPolicy.Builder", "getPrimaryConnectionAttempts", "getPrimaryMaximumRecheckInterval",
                "resetPrimaryRecheckBackoff");
        requireLinkedCompanions("mailer/config/MailSendRetryPolicy.Builder", "getMaximumAttempts", "getRetryDelay", "getMemoryLimit");
        requireLinkedCompanions("email/config/DkimConfig.DkimConfigBuilder", "getDkimPrivateKeyData", "clearSigningAlgorithm",
                "clearExcludedHeadersFromDkimDefaultSigningList");
        requireLinkedCompanions("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "getPkcs12Config", "clearSignatureAlgorithm");
        requireLinkedCompanions("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "getX509Certificate", "clearCipherAlgorithm",
                "clearKeyEncapsulationAlgorithm");
        requireLinkedCompanions("email/config/DeliveryStatusNotification.DeliveryStatusNotificationBuilder", "getReturnOption", "getNotifyOptions",
                "getEnvelopeId", "clearNotifyOptions", "clearEnvelopeId");
        requireLinkedCompanions("mailer/config/Pkcs12Config.Pkcs12ConfigBuilder", "getPkcs12StoreData", "getStorePassword", "getKeyAlias", "getKeyPassword");
        requireLinkedCompanions("email/config/OpenPgpSigningConfig.OpenPgpSigningConfigBuilder", "getSecretKeyRing", "getPassphrase",
                "getSigningKeyId", "getHashAlgorithm");
        requireLinkedCompanions("email/config/OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder", "getRecipientPublicKeyRings",
                "clearRecipientPublicKeyRings", "getSymmetricAlgorithm");
        requireLinkedCompanions("email/config/OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder", "getVerificationKeyRings", "clearVerificationKeyRings",
                "getDecryptionKeyRings", "clearDecryptionKeyRings", "getPassphraseProvider", "clearPassphraseProvider");
        requireText("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "SHA384withRSAandMGF1");
        requireText("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "examples, not a whitelist");
        requireText("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "RSA_OAEP_SHA512");
        requireText("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "DES_EDE3_CBC");
        requireText("email/config/DkimConfig.DkimConfigBuilder", "SHA256_WITH_ED25519");
        requireLinkedCompanions("mailer/MailerGenericBuilder", "getDebugOutput", "resetDebugLogging", "clearDebugPrinter", "clearDebugOutput");
        requireLinkedCompanions("mailer/MailerGenericBuilder", "getFailoverMailer", "getFailoverPolicy");
        if (read("email/Recipients").contains("id=\"&lt;init&gt;()\"")) {
            throw new IllegalStateException("Recipients Javadoc must not advertise a nonexistent no-argument constructor");
        }
        if (Files.exists(documentation.resolve("org/simplejavamail/api/email/OpenPgpDetails.html"))) {
            throw new IllegalStateException("OpenPGP result assembly must not appear in the application API");
        }
        if (read("email/Email").contains("&lt;init&gt;(")) {
            throw new IllegalStateException("Email Javadoc must direct applications to buildEmail(), not internal construction (ADR 0031)");
        }
        if (Files.exists(documentation.resolve("org/simplejavamail/api/email/EmailWithDefaultsAndOverridesApplied.html"))
                || read("email/EmailPopulatingBuilder").contains("EmailWithDefaultsAndOverridesApplied")) {
            throw new IllegalStateException("Internal defaults/overrides bookkeeping must not appear in the application API (ADR 0031)");
        }
    }

    private void requireGetters(final String type, final String... methods) throws IOException {
        final String html = read(type);
        for (final String method : methods) {
            final String anchor = "id=\"" + method + "()\"";
            final int start = html.indexOf(anchor);
            if (start < 0) {
                throw new IllegalStateException(type + " is missing published getter " + method + " (ADR 0031)");
            }
            // JDK 11 uses anchor/list markup; newer JDKs use sections. Both publish the same method IDs.
            final Matcher nextMethod = Pattern.compile("id=\"[^\"]+\\([^\"]*\\)\"").matcher(html);
            final int end = nextMethod.find(start + anchor.length()) ? nextMethod.start() : html.length();
            if (!html.substring(start, end).contains("class=\"block\"")) {
                throw new IllegalStateException(type + "#" + method + " needs a documented getter contract (ADR 0031)");
            }
        }
    }

    private void requireText(final String type, final String text) throws IOException {
        if (!read(type).contains(text)) {
            throw new IllegalStateException(type + " is missing documented guidance or its source-of-truth link: " + text);
        }
    }

    private void requireLinkedCompanions(final String type, final String... methods) throws IOException {
        final String html = read(type);
        for (final String method : methods) {
            final String anchor = "id=\"" + method + "()\"";
            final int start = html.indexOf(anchor);
            if (start < 0) {
                throw new IllegalStateException(type + " is missing published companion " + method + " (ADR 0031)");
            }
            final Matcher nextMethod = Pattern.compile("id=\"[^\"]+\\([^\"]*\\)\"").matcher(html);
            final int end = nextMethod.find(start + anchor.length()) ? nextMethod.start() : html.length();
            if (!html.substring(start, end).contains("href=\"#")) {
                throw new IllegalStateException(type + "#" + method + " needs a link to its setting contract (ADR 0031)");
            }
        }
    }

    private String read(final String type) throws IOException {
        return Files.readString(documentation.resolve("org/simplejavamail/api/" + type + ".html"));
    }
}
