package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.EmailProperty.OPENPGP_ENCRYPTION_CONFIG;
import static org.simplejavamail.api.email.EmailProperty.OPENPGP_SIGNING_CONFIG;
import static org.simplejavamail.api.email.EmailProperty.SMIME_ENCRYPTION_CONFIG;

class ProtectionFamilyBuildValidationTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private final X509Certificate certificate = mock(X509Certificate.class);

    @ParameterizedTest
    @MethodSource("recipientProtectionChoices")
    void recipientCertificatesRejectOpenPgpSigningAndEncryptionAtBuild(final RecipientType type, final String protection) {
        final Recipient recipient = certified(type);
        final EmailPopulatingBuilder builder = openPgp(blank().withRecipients(recipient), protection);

        assertThatThrownBy(builder::buildEmail).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S/MIME and OpenPGP/MIME are both configured")
                .hasMessageContaining("recipient alice@example.org (" + type + ")")
                .hasMessageContaining("S/MIME encryption")
                .hasMessageContaining(protection.equals("encryption") ? "OpenPGP encryption" : "OpenPGP signing");
        assertThat(builder.getRecipients()).containsExactly(recipient);
        verifyNoInteractions(certificate);
    }

    static Stream<Arguments> recipientProtectionChoices() {
        return Stream.of(TO, CC, BCC).flatMap(type -> Stream.of("signing", "encryption", "both")
                .map(protection -> Arguments.of(type, protection)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupDefaultAndFixedCertificatesAreActiveEncryptionRequests(final boolean fixed) {
        final Recipient original = new Recipient("Alice", "alice@example.org", BCC, null);
        final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(original);
        final Collection<Recipient> recipients = (fixed ? group.withFixedSmimeCertificate(certificate)
                : group.withDefaultSmimeCertificate(certificate)).buildRecipients();

        assertThatThrownBy(() -> openPgp(blank().withRecipients(recipients), "encryption").buildEmail())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient alice@example.org (Bcc)");
        assertThat(original.getSmimeCertificate()).isNull();
        assertThat(recipients).singleElement().satisfies(recipient ->
                assertThat(recipient.getSmimeCertificate()).isSameAs(certificate));
    }

    @Test
    void clearingEmailSmimeLeavesRecipientRequestsAndDeliberateStrippingKeepsTheOriginal() {
        final Recipient alice = certified(TO);
        final Recipient audit = new Recipient(null, "audit@example.org", CC, null);
        final Email original = blank().withRecipients(alice, audit, alice)
                .signWithSmime(mock(SmimeSigningConfig.class)).buildEmail();
        final EmailPopulatingBuilder copy = openPgp(mail.emailBuilder().copying(original).clearSmime(), "both");

        assertThatThrownBy(copy::buildEmail).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clearSmime() leaves recipient certificates intact")
                .hasMessageContaining("clearingSmimeCertificates()");
        final Collection<Recipient> withoutCertificates = mail.recipientsBuilder().withRecipients(original.getRecipients())
                .clearingSmimeCertificates().buildRecipients();
        final Email pgp = copy.clearRecipients().withRecipients(withoutCertificates).buildEmail();

        assertThat(pgp.getRecipients()).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "audit@example.org", "alice@example.org");
        assertThat(pgp.getRecipients()).extracting(Recipient::getType).containsExactly(TO, CC, TO);
        assertThat(pgp.getRecipients()).allSatisfy(recipient -> assertThat(recipient.getSmimeCertificate()).isNull());
        assertThat(pgp.getOpenPgpSigningConfig()).isNotNull();
        assertThat(pgp.getOpenPgpEncryptionConfig()).isNotNull();
        assertThat(original.getRecipients()).containsExactly(alice, audit, alice);
        assertThat(original.getSmimeSigningConfig()).isNotNull();
        assertThat(alice.getSmimeCertificate()).isSameAs(certificate);
    }

    @Test
    void errorsIdentifyEmailLevelChoicesWithoutInspectingCryptographicMaterial() {
        final SmimeSigningConfig signing = mock(SmimeSigningConfig.class);
        final SmimeEncryptionConfig encryption = mock(SmimeEncryptionConfig.class);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class)) {
            assertThatThrownBy(() -> openPgp(blank().signWithSmime(signing).encryptWithSmime(encryption), "both").buildEmail())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email-level S/MIME signing").hasMessageContaining("email-level S/MIME encryption")
                    .hasMessageContaining("OpenPGP signing").hasMessageContaining("OpenPGP encryption");
            modules.verifyNoInteractions();
        }
        verifyNoInteractions(signing, encryption);
    }

    @Test
    void conflictIsRejectedBeforeDynamicImagesBorrowedContentOrOptionalModulesAreAccessed() throws Exception {
        final DataSource content = mock(DataSource.class);
        final URL base = new URL(null, "https://example.invalid/", new URLStreamHandler() {
            @Override protected URLConnection openConnection(final URL url) {
                throw new AssertionError("Conflicting protection must fail before resolving images");
            }
        });
        final EmailPopulatingBuilder builder = openPgp(blank().withRecipients(certified(TO))
                .withAttachment("borrowed.bin", content).withHTMLText("<img src='photo.png'>")
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(true)
                        .withEmbeddedImageBaseUrl(base).embeddedImageAutoResolutionMustBeSuccesful(true).build()), "signing");

        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class)) {
            assertThatThrownBy(builder::buildEmail).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recipient alice@example.org");
            modules.verifyNoInteractions();
        }
        verify(content, never()).getInputStream();
        verifyNoInteractions(certificate);
        assertThat(builder.getHTMLText()).isEqualTo("<img src='photo.png'>");
        assertThat(builder.getEmbeddedImages()).isEmpty();
    }

    @Test
    void incompleteSingleFamilyTemplatesAndDkimRemainMetadataOnly() throws Exception {
        final DataSource content = mock(DataSource.class);
        final SmimeSigningConfig signing = mock(SmimeSigningConfig.class);
        final SmimeEncryptionConfig encryption = mock(SmimeEncryptionConfig.class);
        final DkimConfig dkim = mock(DkimConfig.class);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class)) {
            final Email smime = blank().withRecipients(certified(TO), certified(CC), certified(BCC))
                    .withAttachment("borrowed.bin", content).signWithSmime(signing).encryptWithSmime(encryption)
                    .signWithDomainKey(dkim).buildEmail();
            final Email pgp = openPgp(blank().signWithDomainKey(dkim).withAttachment("borrowed.bin", content), "both").buildEmail();
            assertThat(smime.getFromRecipient()).isNull();
            assertThat(smime.getDkimConfig()).isSameAs(dkim);
            assertThat(smime.getSmimeSigningConfig()).isSameAs(signing);
            assertThat(smime.getSmimeEncryptionConfig()).isSameAs(encryption);
            assertThat(pgp.getFromRecipient()).isNull();
            assertThat(pgp.getDkimConfig()).isSameAs(dkim);
            modules.verifyNoInteractions();
        }
        verify(content, never()).getInputStream();
        verifyNoInteractions(certificate, signing, encryption, dkim);
    }

    @Test
    void certificatesOutsideTheContentRecipientsDoNotSelectSmimeEncryption() {
        final Recipient certified = certified(TO);
        final Email pgp = openPgp(blank().from(certified).withReplyTo(certified).withOverrideReceivers(certified)
                .to("bob@example.org"), "both").buildEmail();

        assertThat(pgp.getOpenPgpSigningConfig()).isNotNull();
        assertThat(pgp.getOpenPgpEncryptionConfig()).isNotNull();
        assertThat(pgp.getRecipients()).singleElement().satisfies(recipient -> assertThat(recipient.getSmimeCertificate()).isNull());
        assertThat(certified.getSmimeCertificate()).isSameAs(certificate);
        assertThat(pgp.getOverrideReceivers()).containsExactly(certified);
        verifyNoInteractions(certificate);
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void completionRejectsConflictsFromEitherTemplateInEitherDirection(final boolean inheritedPgp,
            final boolean override, final boolean encrypt) {
        final Email withCertificate = blank().withRecipients(certified(CC)).buildEmail();
        final Email withPgp = openPgp(blank(), encrypt ? "encryption" : "signing").buildEmail();
        final Email template = inheritedPgp ? withPgp : withCertificate;
        final Email supplied = inheritedPgp ? withCertificate : withPgp;
        final EmailGovernanceImpl governance = governance(override ? null : template, override ? template : null);

        assertThatThrownBy(() -> governance.produceEmailApplyingDefaultsAndOverrides(supplied))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient alice@example.org (Cc)");
        assertThatThrownBy(() -> mail.emailBuilder().copying(supplied).buildEmailCompletedWithDefaultsAndOverrides(governance))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient alice@example.org (Cc)");
        assertThat(withCertificate.getOpenPgpSigningConfig()).isNull();
        assertThat(withCertificate.getOpenPgpEncryptionConfig()).isNull();
        assertThat(withCertificate.getRecipients()).singleElement().satisfies(recipient ->
                assertThat(recipient.getSmimeCertificate()).isSameAs(certificate));
        assertThat(withPgp.getRecipients()).isEmpty();
    }

    @Test
    void ordinaryTemplateSuppressionCanExcludeTheUnwantedOpenPgpChoice() {
        final Email pgpTemplate = openPgp(blank(), "both").buildEmail();
        final Email supplied = blank().withRecipients(certified(TO))
                .withTemplateOptions(EmailTemplateOptions.builder()
                        .dontApplyDefaultValueFor(OPENPGP_SIGNING_CONFIG, OPENPGP_ENCRYPTION_CONFIG)
                        .dontApplyOverrideValueFor(OPENPGP_SIGNING_CONFIG, OPENPGP_ENCRYPTION_CONFIG).build()).buildEmail();
        final Email completed = governance(pgpTemplate, pgpTemplate).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertThat(completed.getRecipients()).containsExactlyElementsOf(supplied.getRecipients());
        assertThat(completed.getOpenPgpSigningConfig()).isNull();
        assertThat(completed.getOpenPgpEncryptionConfig()).isNull();
        assertThat(pgpTemplate.getOpenPgpSigningConfig()).isNotNull();
        assertThat(pgpTemplate.getOpenPgpEncryptionConfig()).isNotNull();
    }

    @Test
    void suppressionCannotDiscardMandatorySmimeConfiguration() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.smime.encryption.certificate", "src/test/resources/pkcs12/smime_test_user.pem.standard.crt")).load());
        final Email provided = openPgp(blank(), "signing").withTemplateOptions(EmailTemplateOptions.builder()
                .ignoringDefaults().ignoringOverrides().dontApplyDefaultValueFor(SMIME_ENCRYPTION_CONFIG)
                .dontApplyOverrideValueFor(SMIME_ENCRYPTION_CONFIG).build()).buildEmail();
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email-level S/MIME encryption")
                    .hasMessageContaining("OpenPGP signing");
        }
    }

    @Test
    void exactEmlDoesNotApplyOrdinaryProtectionTemplates() throws Exception {
        final byte[] source = "From: sender@example.org\r\nTo: alice@example.org\r\n\r\nAuthoritative body\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        final Email exact = mail.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("alice@example.org").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(blank().withRecipients(certified(TO)).buildEmail())
                .withEmailOverrides(openPgp(blank(), "both").buildEmail()).buildMailer()) {
            assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(source);
            assertThat(exact.getRecipients()).allSatisfy(recipient -> assertThat(recipient.getSmimeCertificate()).isNull());
        }
        verifyNoInteractions(certificate);
    }

    private EmailPopulatingBuilder blank() {
        return mail.emailBuilder().startingBlank();
    }

    private Recipient certified(final RecipientType type) {
        return new Recipient("Alice", "alice@example.org", type, certificate);
    }

    private EmailGovernanceImpl governance(final Email defaults, final Email overrides) {
        return new EmailGovernanceImpl(null, defaults, overrides, null);
    }

    private static EmailPopulatingBuilder openPgp(final EmailPopulatingBuilder builder, final String protection) {
        if (!protection.equals("encryption")) {
            builder.signWithOpenPgp(OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1}).build());
        }
        if (!protection.equals("signing")) {
            builder.encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{2}).build());
        }
        return builder;
    }
}
