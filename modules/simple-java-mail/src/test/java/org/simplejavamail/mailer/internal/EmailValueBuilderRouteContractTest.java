package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.FULL_MESSAGE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.KEY_BYTES;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.value;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Every frozen value-builder overload is invoked with a checked member/ownership result. */
class EmailValueBuilderRouteContractTest {
    static Stream<Method> routes() {
        return EmailContractTestSupport.routes(ContentEncodingConfig.Builder.class, DeliveryStatusNotification.DeliveryStatusNotificationBuilder.class,
                DkimConfig.DkimConfigBuilder.class, EmailTemplateOptions.Builder.class, ImageResolutionConfig.Builder.class,
                OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder.class, OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder.class,
                OpenPgpSigningConfig.OpenPgpSigningConfigBuilder.class, SmimeEncryptionConfig.SmimeEncryptionConfigBuilder.class,
                SmimeSigningConfig.SmimeSigningConfigBuilder.class, SmtpDeliveryOptions.Builder.class, Pkcs12Config.Pkcs12ConfigBuilder.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyValueInputRouteStoresItsIntendedChoice(final Method method, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final Class<?> owner = method.getDeclaringClass();
        if (owner == ContentEncodingConfig.Builder.class) { encoding(method); }
        else if (owner == DeliveryStatusNotification.DeliveryStatusNotificationBuilder.class) { notification(method); }
        else if (owner == EmailTemplateOptions.Builder.class) { templateOptions(method); }
        else if (owner == ImageResolutionConfig.Builder.class) { images(method); }
        else if (owner == DkimConfig.DkimConfigBuilder.class) { dkim(method, fixture); }
        else if (owner == Pkcs12Config.Pkcs12ConfigBuilder.class) { pkcs12(method, fixture); }
        else if (owner == SmimeEncryptionConfig.SmimeEncryptionConfigBuilder.class) { smimeEncryption(method, fixture); }
        else if (owner == SmimeSigningConfig.SmimeSigningConfigBuilder.class) { smimeSigning(method, fixture); }
        else if (owner == OpenPgpSigningConfig.OpenPgpSigningConfigBuilder.class) { pgpSigning(method); }
        else if (owner == OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder.class) { pgpEncryption(method); }
        else if (owner == OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder.class) { pgpReceive(method); }
        else if (owner == SmtpDeliveryOptions.Builder.class) { delivery(method); }
        else { throw new AssertionError("No value-builder fixture for " + method); }
    }

    private static void encoding(final Method method) throws Exception {
        final ContentEncodingConfig.Builder builder = ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BIT7)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT7).withHTMLTextContentTransferEncoding(ContentTransferEncoding.BIT7)
                .withCalendarTextContentTransferEncoding(ContentTransferEncoding.BIT7);
        final boolean clear = method.getName().startsWith("clear");
        assertThat(invoke(builder, method, clear ? new Object[0] : new Object[] {ContentTransferEncoding.BASE_64})).isSameAs(builder);
        final String member = method.getName().substring(clear ? 5 : 4);
        assertThat(value(builder.build(), "get" + member)).isEqualTo(clear ? null : ContentTransferEncoding.BASE_64);
    }

    private static void notification(final Method method) {
        final DeliveryStatusNotification.DeliveryStatusNotificationBuilder builder = DeliveryStatusNotification.builder()
                .notifyOptions(FAILURE).returnOption(FULL_MESSAGE).envelopeId("old");
        final String name = method.getName();
        Object[] arguments = new Object[0];
        if (name.equals("envelopeId")) { arguments = new Object[] {"new"}; }
        if (name.equals("notifyOptions")) { arguments = new Object[] {method.getParameterTypes()[0] == String.class ? "SUCCESS" : new DeliveryStatusNotification.NotifyOption[] {SUCCESS}}; }
        if (name.equals("returnOption")) { arguments = new Object[] {method.getParameterTypes()[0] == String.class ? "HDRS" : HEADERS_ONLY}; }
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final DeliveryStatusNotification result = builder.build();
        if (name.contains("EnvelopeId") || name.equals("envelopeId")) { assertThat(result.getEnvelopeId()).isEqualTo(name.startsWith("clear") ? null : "new"); }
        else if (name.contains("NotifyOptions") || name.equals("notifyOptions")) { assertThat(result.getNotifyOptions()).isEqualTo(name.startsWith("clear") ? Set.of() : Set.of(SUCCESS)); }
        else { assertThat(result.getReturnOption()).isEqualTo(name.startsWith("clear") ? null : HEADERS_ONLY); }
    }

    private static void templateOptions(final Method method) {
        final EmailTemplateOptions.Builder builder = EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides()
                .dontApplyDefaultValueFor(EmailProperty.SUBJECT, EmailProperty.BODY_TEXT)
                .dontApplyOverrideValueFor(EmailProperty.SUBJECT, EmailProperty.BODY_TEXT);
        final String name = method.getName();
        final Object[] arguments = method.getParameterCount() == 0 ? new Object[0]
                : method.getParameterTypes()[0] == boolean.class ? new Object[] {false}
                : new Object[] {new EmailProperty[] {name.startsWith("dont") ? EmailProperty.HEADERS : EmailProperty.SUBJECT}};
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final EmailTemplateOptions result = builder.build();
        final boolean defaults = name.contains("Default");
        if (name.startsWith("ignoring") || name.startsWith("resetIgnore")) {
            assertThat(defaults ? result.isIgnoreDefaults() : result.isIgnoreOverrides()).isEqualTo(name.startsWith("ignoring") && method.getParameterCount() == 0);
            record("field", "email.options." + (defaults ? "ignoreDefaults" : "ignoreOverrides"));
        } else {
            final Set<EmailProperty> selected = defaults ? result.getPropertiesNotToApplyDefaultValueFor() : result.getPropertiesNotToApplyOverrideValueFor();
            if (name.startsWith("clear")) { assertThat(selected).isNullOrEmpty(); }
            else { assertThat(selected).containsExactlyInAnyOrderElementsOf(name.startsWith("dont")
                    ? Set.of(EmailProperty.SUBJECT, EmailProperty.BODY_TEXT, EmailProperty.HEADERS) : Set.of(EmailProperty.BODY_TEXT)); }
            record("field", "email.options." + (defaults ? "defaultSuppressions" : "overrideSuppressions"));
        }
    }

    private static void images(final Method method) throws Exception {
        final ImageResolutionConfig.Builder builder = ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(false)
                .withEmbeddedImageAutoResolutionForClassPathResources(false).withEmbeddedImageAutoResolutionForURLs(false)
                .withEmbeddedImageBaseDir("old").withEmbeddedImageBaseClassPath("old").withEmbeddedImageBaseUrl("https://old.example/")
                .allowingEmbeddedImageOutsideBaseDir(false).allowingEmbeddedImageOutsideBaseClassPath(false).allowingEmbeddedImageOutsideBaseUrl(false)
                .embeddedImageAutoResolutionMustBeSuccesful(false);
        final String name = method.getName();
        final String member = name.startsWith("with") ? name.substring(4) : name.startsWith("allowing") ? "Allow" + name.substring(8)
                : name.startsWith("clear") || name.startsWith("reset") ? name.substring(5)
                : name.startsWith("useConfigured") ? name.substring(13) : "EmbeddedImageAutoResolutionMustBeSuccesful";
        final boolean base = member.startsWith("EmbeddedImageBase");
        final Object[] arguments = method.getParameterCount() == 0 ? new Object[0] : new Object[] {
                method.getParameterTypes()[0] == boolean.class ? true : member.endsWith("Url")
                        ? method.getParameterTypes()[0] == URL.class ? new URL("https://new.example/") : "https://new.example/" : "new"};
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final ImageResolutionConfig result = builder.build();
        final Object expected;
        if (name.startsWith("clear") || name.startsWith("useConfigured")) { expected = null; }
        else if (name.startsWith("reset")) {
            final Map<String, Boolean> defaults = Map.of("EmbeddedImageAutoResolutionForFiles", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_FILES,
                    "EmbeddedImageAutoResolutionForClassPathResources", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_CLASSPATH_RESOURCES,
                    "EmbeddedImageAutoResolutionForURLs", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_URLS,
                    "AllowEmbeddedImageOutsideBaseDir", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_DIR,
                    "AllowEmbeddedImageOutsideBaseClassPath", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_CLASSPATH,
                    "AllowEmbeddedImageOutsideBaseUrl", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_URL,
                    "EmbeddedImageAutoResolutionMustBeSuccesful", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_MUST_BE_SUCCESSFUL);
            expected = defaults.get(member);
        } else { expected = base ? member.endsWith("Url") ? new URL("https://new.example/") : "new" : true; }
        if (expected instanceof URL) { assertThat(((URL) value(result, "get" + member)).toExternalForm()).isEqualTo(((URL) expected).toExternalForm()); }
        else { assertThat(value(result, "get" + member)).isEqualTo(expected); }
        if (base) { assertThat(value(result, "is" + member + "Configured")).isEqualTo(!name.startsWith("useConfigured")); }
        record("field", "email.image." + Character.toLowerCase(member.charAt(0)) + member.substring(1));
    }

    private static void dkim(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder().dkimPrivateKeyData(new byte[] {1}).dkimSelector("old")
                .dkimSigningDomain("old.example").useLengthParam(false).excludedHeadersFromDkimDefaultSigningList("Old")
                .headerCanonicalization(DkimConfig.Canonicalization.SIMPLE).bodyCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                .signingAlgorithm("old");
        final String name = method.getName();
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(KEY_BYTES);
        final Object[] args;
        if (name.startsWith("clear")) { args = new Object[0]; }
        else if (name.equals("dkimPrivateKeyPath")) { args = new Object[] {pathArgument(method, fixture.keyFile)}; }
        else if (name.equals("dkimPrivateKeyData")) { args = new Object[] {method.getParameterTypes()[0] == byte[].class ? KEY_BYTES
                : method.getParameterTypes()[0] == String.class ? new String(KEY_BYTES, java.nio.charset.StandardCharsets.UTF_8) : stream}; }
        else if (name.endsWith("Canonicalization")) { args = new Object[] {DkimConfig.Canonicalization.RELAXED}; }
        else if (name.equals("useLengthParam")) { args = new Object[] {true}; }
        else if (name.equals("excludedHeadersFromDkimDefaultSigningList")) { args = new Object[] {method.getParameterTypes()[0].isArray() ? new String[] {"X-New"} : Set.of("X-New")}; }
        else { args = new Object[] {"new"}; }
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final DkimConfig result = builder.build();
        if (name.startsWith("dkimPrivateKey")) { assertThat(result.getDkimPrivateKeyData()).containsExactly(KEY_BYTES); }
        else {
            final String member = name.startsWith("clear") ? name.substring(5) : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            final Object expected = name.startsWith("clear") ? null : args[0] instanceof String[] ? Set.of("X-New") : args[0];
            assertThat(value(result, "get" + member)).isEqualTo(expected);
        }
        materialOwnership(method, fixture.keyFile, stream, name.equals("dkimPrivateKeyPath"));
        record("field", "material.dkim");
    }

    private static void pkcs12(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder().pkcs12Store(new byte[] {1})
                .storePassword("old").keyAlias("old").keyPassword("old");
        final String name = method.getName();
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(KEY_BYTES);
        final Object arg = name.equals("pkcs12Store") ? materialArgument(method, fixture.keyFile, KEY_BYTES, stream)
                : method.getParameterTypes()[0] == char[].class ? "new".toCharArray() : "new";
        assertThat(invoke(builder, method, arg)).isSameAs(builder);
        final Pkcs12Config result = builder.build();
        if (name.equals("pkcs12Store")) { assertThat(result.getPkcs12StoreData()).containsExactly(KEY_BYTES); }
        else if (name.equals("keyAlias")) { assertThat(result.getKeyAlias()).isEqualTo("new"); }
        else { assertThat(name.equals("keyPassword") ? result.getKeyPassword() : result.getStorePassword()).containsExactly('n', 'e', 'w'); }
        materialOwnership(method, fixture.keyFile, stream, name.equals("pkcs12Store"));
        record("field", "material.pkcs12");
    }

    private static void smimeEncryption(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder().x509Certificate(fixture.certificate)
                .keyEncapsulationAlgorithm("old").cipherAlgorithm("old");
        final String name = method.getName();
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(Files.readAllBytes(fixture.certificateFile));
        final Object[] args = name.startsWith("clear") ? new Object[0] : new Object[] {name.equals("x509Certificate")
                ? method.getParameterTypes()[0] == java.security.cert.X509Certificate.class ? fixture.certificate
                        : materialArgument(method, fixture.certificateFile, null, stream) : "new"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final SmimeEncryptionConfig result = builder.build();
        if (name.equals("x509Certificate")) { assertThat(result.getX509Certificate().getEncoded()).containsExactly(fixture.certificate.getEncoded()); }
        else { assertThat(name.contains("Cipher") || name.equals("cipherAlgorithm") ? result.getCipherAlgorithm() : result.getKeyEncapsulationAlgorithm())
                .isEqualTo(name.startsWith("clear") ? null : "new"); }
        materialOwnership(method, fixture.certificateFile, stream, name.equals("x509Certificate"));
        record("field", "material.x509");
    }

    private static void smimeSigning(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder().pkcs12Config(fixture.store).signatureAlgorithm("old");
        final String name = method.getName();
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(KEY_BYTES);
        final Object[] args = name.startsWith("clear") ? new Object[0] : name.equals("signatureAlgorithm") ? new Object[] {"new"}
                : method.getParameterTypes()[0] == Pkcs12Config.class ? new Object[] {fixture.store}
                : new Object[] {materialArgument(method, fixture.keyFile, KEY_BYTES, stream), "store", "alias", "key"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final SmimeSigningConfig result = builder.build();
        if (name.equals("pkcs12Config")) { assertThat(result.getPkcs12Config().getPkcs12StoreData())
                .containsExactly(method.getParameterTypes()[0] == Pkcs12Config.class ? fixture.store.getPkcs12StoreData() : KEY_BYTES); }
        else { assertThat(result.getSignatureAlgorithm()).isEqualTo(name.startsWith("clear") ? null : "new"); }
        materialOwnership(method, fixture.keyFile, stream, name.equals("pkcs12Config"));
        record("field", "material.pkcs12");
    }

    private static void pgpSigning(final Method method) {
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder builder = OpenPgpSigningConfig.builder().secretKeyRing(new byte[] {1})
                .passphrase("old").signingKeyId(1).hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        final String name = method.getName();
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {name.equals("secretKeyRing") ? KEY_BYTES
                : name.equals("signingKeyId") ? 2L : name.equals("hashAlgorithm") ? OpenPgpSigningConfig.HashAlgorithm.SHA384
                : method.getParameterTypes()[0] == char[].class ? "new".toCharArray() : "new"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final OpenPgpSigningConfig result = builder.build();
        if (name.equals("secretKeyRing")) { assertThat(result.getSecretKeyRing()).containsExactly(KEY_BYTES); }
        else if (name.equals("passphrase")) { assertThat(result.getPassphrase()).containsExactly('n', 'e', 'w'); }
        else if (name.contains("SigningKeyId") || name.equals("signingKeyId")) { assertThat(result.getSigningKeyId()).isEqualTo(name.startsWith("clear") ? null : 2L); }
        else { assertThat(result.getHashAlgorithm()).isEqualTo(name.startsWith("reset") ? OpenPgpSigningConfig.HashAlgorithm.SHA256 : OpenPgpSigningConfig.HashAlgorithm.SHA384); }
        record("field", "material.openpgpSigning");
    }

    private static void pgpEncryption(final Method method) {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {1})
                .symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        final String name = method.getName();
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {name.equals("symmetricAlgorithm")
                ? OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_192 : name.equals("recipientPublicKeyRings") ? List.of(KEY_BYTES) : KEY_BYTES};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        if (name.equals("clearRecipientPublicKeyRings")) { assertThat(builder.getRecipientPublicKeyRings()).isEmpty(); }
        else {
            final OpenPgpEncryptionConfig result = builder.build();
            if (name.contains("Algorithm")) { assertThat(result.getSymmetricAlgorithm()).isEqualTo(name.startsWith("reset")
                    ? OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_256 : OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_192); }
            else { assertThat(result.getRecipientPublicKeyRings()).hasSize(name.startsWith("add") ? 2 : 1);
                assertThat(result.getRecipientPublicKeyRings().get(name.startsWith("add") ? 1 : 0)).containsExactly(KEY_BYTES); }
        }
        record("field", "material.openpgpEncryption");
    }

    private static void pgpReceive(final Method method) {
        final OpenPgpReceiveConfig.PassphraseProvider oldProvider = key -> "old".toCharArray();
        final OpenPgpReceiveConfig.PassphraseProvider newProvider = key -> "new".toCharArray();
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder().addVerificationKeyRing(new byte[] {1})
                .addDecryptionKeyRing(new byte[] {1}, "old").passphraseProvider(oldProvider);
        final String name = method.getName();
        final Object[] args = name.startsWith("clear") ? new Object[0] : name.equals("passphraseProvider") ? new Object[] {newProvider}
                : name.equals("addVerificationKeyRing") ? new Object[] {KEY_BYTES}
                : new Object[] {KEY_BYTES, method.getParameterTypes()[1] == char[].class ? "new".toCharArray() : "new"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final OpenPgpReceiveConfig result = builder.build();
        if (name.contains("Verification")) { assertThat(result.getVerificationKeyRings()).hasSize(name.startsWith("clear") ? 0 : 2); }
        else if (name.contains("Decryption")) { assertThat(result.getDecryptionKeyRings()).hasSize(name.startsWith("clear") ? 0 : 2);
            if (!name.startsWith("clear")) { assertThat(result.getDecryptionKeyRings().get(1).getKeyRing()).containsExactly(KEY_BYTES);
                assertThat(result.getDecryptionKeyRings().get(1).getPassphrase()).containsExactly('n', 'e', 'w'); } }
        else { assertThat(result.getPassphraseProvider()).isSameAs(name.startsWith("clear") ? null : newProvider); }
        record("field", "material.openpgpReceive");
    }

    private static void delivery(final Method method) {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder().withDeliveryStatusNotification(FULL_MESSAGE, FAILURE)
                .fixingEnvelopeId("old").withSendingToAcceptedRecipients(false).withTlsRequiredForOnwardDelivery();
        final String name = method.getName();
        Object[] args = new Object[0];
        if (name.equals("withSendingToAcceptedRecipients")) { args = new Object[] {true}; }
        else if (name.equals("fixingEnvelopeId")) { args = new Object[] {"new"}; }
        else if (name.equals("withDeliveryStatusNotificationReturnOption")) { args = new Object[] {HEADERS_ONLY}; }
        else if (name.equals("withDeliveryStatusNotificationNotifyOptions")) { args = new Object[] {new DeliveryStatusNotification.NotifyOption[] {SUCCESS}}; }
        else if (name.equals("withDeliveryStatusNotification")) {
            args = method.getParameterCount() == 2 ? new Object[] {HEADERS_ONLY, new DeliveryStatusNotification.NotifyOption[] {SUCCESS}}
                    : method.getParameterTypes()[0] == DeliveryStatusNotification.class ? new Object[] {DeliveryStatusNotification.of(HEADERS_ONLY, SUCCESS)}
                    : new Object[] {new DeliveryStatusNotification.NotifyOption[] {SUCCESS}};
        }
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final SmtpDeliveryOptions result = builder.build();
        if (name.contains("SendingToAccepted")) { assertThat(result.getSendingToAcceptedRecipients()).isEqualTo(name.startsWith("clear") ? null : true); }
        else if (name.contains("TlsRequired")) { assertThat(result.isTlsRequiredForOnwardDelivery()).isEqualTo(!name.startsWith("clear")); }
        else if (name.equals("clearDeliveryStatusNotification")) { assertThat(result.getDeliveryStatusNotification()).isNull(); }
        else if (name.contains("EnvelopeId")) { assertThat(result.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo(name.startsWith("clear") ? null : "new"); }
        else if (name.contains("ReturnOption")) { assertThat(result.getDeliveryStatusNotification().getReturnOption()).isEqualTo(name.startsWith("clear") ? null : HEADERS_ONLY); }
        else { assertThat(result.getDeliveryStatusNotification().getNotifyOptions()).isEqualTo(name.startsWith("clear") ? Set.of() : Set.of(SUCCESS)); }
    }

    private static Object pathArgument(final Method method, final Path path) {
        return method.getParameterTypes()[0] == Path.class ? path : method.getParameterTypes()[0] == java.io.File.class ? path.toFile() : path.toString();
    }

    private static Object materialArgument(final Method method, final Path path, final byte[] bytes, final EmailContractTestSupport.BorrowedInput stream) {
        return method.getParameterTypes()[0] == byte[].class ? bytes : method.getParameterTypes()[0] == java.io.InputStream.class ? stream : pathArgument(method, path);
    }

    private static void materialOwnership(final Method method, final Path path, final EmailContractTestSupport.BorrowedInput stream,
            final boolean pathInput) throws Exception {
        if (method.getParameterCount() == 0) { return; }
        final Class<?> type = method.getParameterTypes()[0];
        if (type == java.io.InputStream.class) {
            assertThat(stream.closed).isFalse();
            record("rule", "material.ownership");
        } else if (pathInput && (type == Path.class || type == java.io.File.class || type == String.class)) {
            Files.delete(path);
            assertThat(Files.exists(path)).isFalse();
            record("rule", "material.ownership");
        }
    }
}
