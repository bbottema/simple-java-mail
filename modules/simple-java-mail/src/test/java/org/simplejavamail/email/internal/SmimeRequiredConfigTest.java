package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SmimeRequiredConfigTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void missingSigningConfigOnAFreshBuilderFailsWithoutChangingItsChoices() {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();

        assertThatThrownBy(() -> builder.signWithSmime((SmimeSigningConfig) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeSigningConfig is required");

        assertThat(builder.getSmimeSigningConfig()).isNull();
        assertThat(builder.getSmimeEncryptionConfig()).isNull();
    }

    @Test
    void missingEncryptionConfigOnAFreshBuilderFailsWithoutChangingItsChoices() {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();

        assertThatThrownBy(() -> builder.encryptWithSmime((SmimeEncryptionConfig) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeEncryptionConfig is required");

        assertThat(builder.getSmimeSigningConfig()).isNull();
        assertThat(builder.getSmimeEncryptionConfig()).isNull();
    }

    @Test
    void missingSigningConfigFailsBeforeRemovingTheExistingChoice() {
        final SmimeSigningConfig signing = mock(SmimeSigningConfig.class);
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().signWithSmime(signing);

        assertThatThrownBy(() -> builder.signWithSmime((SmimeSigningConfig) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeSigningConfig is required");

        assertThat(builder.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(builder.buildEmail().getSmimeSigningConfig()).isSameAs(signing);
        verifyNoInteractions(signing);
    }

    @Test
    void missingEncryptionConfigFailsBeforeRemovingTheExistingChoice() {
        final SmimeEncryptionConfig encryption = mock(SmimeEncryptionConfig.class);
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().encryptWithSmime(encryption);

        assertThatThrownBy(() -> builder.encryptWithSmime((SmimeEncryptionConfig) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeEncryptionConfig is required");

        assertThat(builder.getSmimeEncryptionConfig()).isSameAs(encryption);
        assertThat(builder.buildEmail().getSmimeEncryptionConfig()).isSameAs(encryption);
        verifyNoInteractions(encryption);
    }

    @Test
    void nonNullConfigurationsRetainOptionalAlgorithmDefaultsWithoutInspectingKeyMaterial() {
        final Pkcs12Config keys = mock(Pkcs12Config.class);
        final X509Certificate certificate = mock(X509Certificate.class);
        final SmimeSigningConfig signing = SmimeSigningConfig.builder().pkcs12Config(keys).signatureAlgorithm(null).build();
        final SmimeEncryptionConfig encryption = SmimeEncryptionConfig.builder().x509Certificate(certificate)
                .keyEncapsulationAlgorithm(null).cipherAlgorithm(null).build();

        final Email email = mail.emailBuilder().startingBlank().signWithSmime(signing).encryptWithSmime(encryption).buildEmail();

        assertThat(email.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(email.getSmimeEncryptionConfig()).isSameAs(encryption);
        assertThat(signing.getSignatureAlgorithm()).isNull();
        assertThat(encryption.getKeyEncapsulationAlgorithm()).isNull();
        assertThat(encryption.getCipherAlgorithm()).isNull();
        verifyNoInteractions(keys, certificate);
    }

    @Test
    void namedClearsRemainIndependentAndRetainRecipientCertificatesAndEarlierMessages() {
        final SmimeSigningConfig signing = mock(SmimeSigningConfig.class);
        final SmimeEncryptionConfig encryption = mock(SmimeEncryptionConfig.class);
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient recipient = mail.recipientBuilder().withSmimeCertificate(certificate).to("reader@example.org");
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().withRecipients(recipient)
                .signWithSmime(signing).encryptWithSmime(encryption);
        final Email original = builder.buildEmail();

        final Email encryptionOnly = builder.clearSmimeSigning().buildEmail();
        assertThat(encryptionOnly.getSmimeSigningConfig()).isNull();
        assertThat(encryptionOnly.getSmimeEncryptionConfig()).isSameAs(encryption);
        final Email signingOnly = builder.signWithSmime(signing).clearSmimeEncryption().buildEmail();
        assertThat(signingOnly.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(signingOnly.getSmimeEncryptionConfig()).isNull();
        final Email cleared = builder.encryptWithSmime(encryption).clearSmime().buildEmail();
        assertThat(cleared.getSmimeSigningConfig()).isNull();
        assertThat(cleared.getSmimeEncryptionConfig()).isNull();
        assertThat(cleared.getRecipients()).containsExactly(recipient);
        assertThat(cleared.getRecipients().get(0).getSmimeCertificate()).isSameAs(certificate);
        assertThat(original.getSmimeSigningConfig()).isSameAs(signing);
        assertThat(original.getSmimeEncryptionConfig()).isSameAs(encryption);
        verifyNoInteractions(signing, encryption, certificate);
    }
}
