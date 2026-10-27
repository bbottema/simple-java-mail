package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;

import java.net.URL;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.KEY_BYTES;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.captured;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class EmailSourcePropertyContractTest {
    static Stream<Property> properties() {
        return ConfigurationAdoptionCatalogue.PROPERTIES.entrySet().stream().filter(entry -> entry.getValue().name().startsWith("EMAIL"))
                .map(Map.Entry::getKey);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("properties")
    void configuredSourcePropertyReachesItsEmailConsumer(final Property property, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final Map<String, Object> source = new LinkedHashMap<>();
        properties().forEach(candidate -> source.put(candidate.key(), sourceValue(candidate, property, fixture)));
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(source).load());
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
        final Email result = builder.buildEmailCompletedWithDefaultsAndOverrides();
        final String image = imageMember(property);
        if (image != null) {
            final Object expected = sourceValue(property, property, fixture);
            if (image.equals("embeddedImageBaseUrl")) { assertThat(((URL) captured(builder, image)).toExternalForm()).isEqualTo(expected); }
            else { assertThat(captured(builder, image)).isEqualTo(expected); }
            record("field", "email.image." + image);
        } else if (property.name().endsWith("_NAME") || property.name().endsWith("_ADDRESS")) {
            final Recipient recipient = recipient(property, result);
            assertThat(property.name().endsWith("_NAME") ? recipient.getName() : recipient.getAddress())
                    .isEqualTo(sourceValue(property, property, fixture));
        } else { assertMessageProperty(property, result, fixture); }
        record("property", property.key());
    }

    private static Object sourceValue(final Property property, final Property active, final EmailContractTestSupport fixture) {
        final String image = imageMember(property);
        if (image != null) {
            if (image.equals("embeddedImageBaseDir")) { return fixture.textFile.getParent().toString(); }
            if (image.equals("embeddedImageBaseClassPath")) { return "/factory-images"; }
            if (image.equals("embeddedImageBaseUrl")) { return "https://factory.example.org/images/"; }
            final Map<String, Boolean> defaults = Map.of("embeddedImageAutoResolutionForFiles", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_FILES,
                    "embeddedImageAutoResolutionForClassPathResources", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_CLASSPATH_RESOURCES,
                    "embeddedImageAutoResolutionForURLs", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_URLS,
                    "allowEmbeddedImageOutsideBaseDir", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_DIR,
                    "allowEmbeddedImageOutsideBaseClassPath", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_CLASSPATH,
                    "allowEmbeddedImageOutsideBaseUrl", EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_URL,
                    "embeddedImageAutoResolutionMustBeSuccesful", EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_MUST_BE_SUCCESSFUL);
            return property == active ? !defaults.get(image) : defaults.get(image);
        }
        if (property.name().endsWith("_NAME")) { return property.name() + " person"; }
        if (property.name().endsWith("_ADDRESS")) { return property.name().toLowerCase(java.util.Locale.ROOT) + "@example.org"; }
        switch (property) {
            case DEFAULT_SUBJECT: return "Factory subject";
            case DEFAULT_CONTENT_TRANSFER_ENCODING: return ContentTransferEncoding.BIT7;
            case DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING: return ContentTransferEncoding.BASE_64;
            case DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING: return ContentTransferEncoding.BIT8;
            case DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING: return ContentTransferEncoding.BINARY;
            case DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY: return "SUCCESS";
            case DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION: return "HDRS";
            case DEFAULT_REQUIRE_TLS: case DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS: case DKIM_SIGNING_USE_LENGTH_PARAM: return property == active;
            case SMIME_SIGNING_KEYSTORE: return fixture.storeFile.toString();
            case SMIME_SIGNING_KEYSTORE_PASSWORD: return "store-password";
            case SMIME_SIGNING_KEY_ALIAS: return "factory-alias";
            case SMIME_SIGNING_KEY_PASSWORD: return "key-password";
            case SMIME_SIGNING_ALGORITHM: return "SHA256withRSA";
            case SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM: return "RSA";
            case SMIME_ENCRYPTION_CIPHER: return "AES256_CBC";
            case SMIME_ENCRYPTION_CERTIFICATE: return fixture.certificateFile.toString();
            case DKIM_PRIVATE_KEY_FILE_OR_DATA: return "base64:" + Base64.getEncoder().encodeToString(KEY_BYTES);
            case DKIM_SELECTOR: return "factory-selector";
            case DKIM_SIGNING_DOMAIN: return "factory.example.org";
            case DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST: return "X-Factory";
            case DKIM_SIGNING_HEADER_CANONICALIZATION: return DkimConfig.Canonicalization.SIMPLE;
            case DKIM_SIGNING_BODY_CANONICALIZATION: return DkimConfig.Canonicalization.RELAXED;
            case DKIM_SIGNING_ALGORITHM: return "SHA256_WITH_RSA";
            default: throw new AssertionError("No controlled source input for " + property);
        }
    }

    private static Recipient recipient(final Property property, final Email email) {
        if (property.name().contains("FROM")) { return email.getFromRecipient(); }
        if (property.name().contains("REPLYTO")) { return email.getReplyToRecipients().get(0); }
        if (property.name().contains("BOUNCETO")) { return email.getBounceToRecipient(); }
        final jakarta.mail.Message.RecipientType role = property.name().contains("BCC") ? jakarta.mail.Message.RecipientType.BCC
                : property.name().contains("CC") ? jakarta.mail.Message.RecipientType.CC : jakarta.mail.Message.RecipientType.TO;
        return email.getRecipients().stream().filter(value -> value.getType() == role).findFirst().orElseThrow();
    }

    private static void assertMessageProperty(final Property property, final Email email, final EmailContractTestSupport fixture) throws Exception {
        final Pkcs12Config keys = email.getSmimeSigningConfig().getPkcs12Config();
        final DkimConfig dkim = email.getDkimConfig();
        switch (property) {
            case DEFAULT_SUBJECT: assertThat(email.getSubject()).isEqualTo("Factory subject"); break;
            case DEFAULT_CONTENT_TRANSFER_ENCODING: assertThat(email.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7); break;
            case DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING: assertThat(email.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64); break;
            case DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING: assertThat(email.getHTMLTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT8); break;
            case DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING: assertThat(email.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BINARY); break;
            case DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY: assertThat(email.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS); break;
            case DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION: assertThat(email.getDeliveryStatusNotification().getReturnOption()).isEqualTo(org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY); break;
            case DEFAULT_REQUIRE_TLS: assertThat(email.isTlsRequiredForOnwardDelivery()).isTrue(); record("field", "email.TLS_REQUIRED_FOR_ONWARD_DELIVERY"); break;
            case DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS: assertThat(email.getSendingToAcceptedRecipients()).isTrue(); break;
            case SMIME_SIGNING_KEYSTORE: assertThat(keys.getPkcs12StoreData()).containsExactly(java.nio.file.Files.readAllBytes(fixture.storeFile)); break;
            case SMIME_SIGNING_KEYSTORE_PASSWORD: assertThat(keys.getStorePassword()).containsExactly("store-password".toCharArray()); break;
            case SMIME_SIGNING_KEY_ALIAS: assertThat(keys.getKeyAlias()).isEqualTo("factory-alias"); break;
            case SMIME_SIGNING_KEY_PASSWORD: assertThat(keys.getKeyPassword()).containsExactly("key-password".toCharArray()); break;
            case SMIME_SIGNING_ALGORITHM: assertThat(email.getSmimeSigningConfig().getSignatureAlgorithm()).isEqualTo("SHA256withRSA"); break;
            case SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM: assertThat(email.getSmimeEncryptionConfig().getKeyEncapsulationAlgorithm()).isEqualTo("RSA"); break;
            case SMIME_ENCRYPTION_CIPHER: assertThat(email.getSmimeEncryptionConfig().getCipherAlgorithm()).isEqualTo("AES256_CBC"); break;
            case SMIME_ENCRYPTION_CERTIFICATE: assertThat(email.getSmimeEncryptionConfig().getX509Certificate().getEncoded()).containsExactly(fixture.certificate.getEncoded()); break;
            case DKIM_PRIVATE_KEY_FILE_OR_DATA: assertThat(dkim.getDkimPrivateKeyData()).containsExactly(KEY_BYTES); break;
            case DKIM_SELECTOR: assertThat(dkim.getDkimSelector()).isEqualTo("factory-selector"); break;
            case DKIM_SIGNING_DOMAIN: assertThat(dkim.getDkimSigningDomain()).isEqualTo("factory.example.org"); break;
            case DKIM_SIGNING_USE_LENGTH_PARAM: assertThat(dkim.getUseLengthParam()).isTrue(); break;
            case DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST: assertThat(dkim.getExcludedHeadersFromDkimDefaultSigningList()).containsExactly("X-Factory"); break;
            case DKIM_SIGNING_HEADER_CANONICALIZATION: assertThat(dkim.getHeaderCanonicalization()).isEqualTo(DkimConfig.Canonicalization.SIMPLE); break;
            case DKIM_SIGNING_BODY_CANONICALIZATION: assertThat(dkim.getBodyCanonicalization()).isEqualTo(DkimConfig.Canonicalization.RELAXED); break;
            case DKIM_SIGNING_ALGORITHM: assertThat(dkim.getSigningAlgorithm()).isEqualTo("SHA256_WITH_RSA"); break;
            default: throw new AssertionError("No observed Email consumer for " + property);
        }
    }

    private static String imageMember(final Property property) {
        switch (property) {
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR: return "embeddedImageAutoResolutionForFiles";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH: return "embeddedImageAutoResolutionForClassPathResources";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL: return "embeddedImageAutoResolutionForURLs";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR: return "embeddedImageBaseDir";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH: return "embeddedImageBaseClassPath";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL: return "embeddedImageBaseUrl";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR: return "allowEmbeddedImageOutsideBaseDir";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH: return "allowEmbeddedImageOutsideBaseClassPath";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL: return "allowEmbeddedImageOutsideBaseUrl";
            case EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL: return "embeddedImageAutoResolutionMustBeSuccesful";
            default: return null;
        }
    }
}
