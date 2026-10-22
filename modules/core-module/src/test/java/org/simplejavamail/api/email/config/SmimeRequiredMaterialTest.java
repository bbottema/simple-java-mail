package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SmimeRequiredMaterialTest {
    @Test
    void signingRejectsMissingInitialMaterialWithoutChangingPreferences() {
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder()
                .signatureAlgorithm("SHA384withRSA");

        assertThatThrownBy(() -> builder.pkcs12Config((Pkcs12Config) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pkcs12Config is required");

        assertThat(builder.getPkcs12Config()).isNull();
        assertThat(builder.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
    }

    @Test
    void encryptionRejectsMissingInitialMaterialWithoutChangingPreferences() {
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder()
                .keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC");

        assertThatThrownBy(() -> builder.x509Certificate((X509Certificate) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("x509Certificate is required");

        assertThat(builder.getX509Certificate()).isNull();
        assertThat(builder.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(builder.getCipherAlgorithm()).isEqualTo("AES256_CBC");
    }

    @Test
    void rejectedSigningReplacementRetainsMaterialPreferencesAndCompletedValue() {
        final Pkcs12Config original = store((byte) 1);
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder()
                .pkcs12Config(original).signatureAlgorithm("SHA384withRSA");
        final SmimeSigningConfig completed = builder.build();

        assertThatThrownBy(() -> builder.pkcs12Config((Pkcs12Config) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pkcs12Config is required");

        assertThat(builder.getPkcs12Config()).isSameAs(original);
        assertThat(builder.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
        assertThat(builder.build().getPkcs12Config()).isSameAs(original);
        assertThat(completed.getPkcs12Config()).isSameAs(original);
        assertThat(completed.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
    }

    @Test
    void rejectedEncryptionReplacementRetainsBorrowedCertificatePreferencesAndCompletedValue() {
        final X509Certificate original = mock(X509Certificate.class);
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder()
                .x509Certificate(original).keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC");
        final SmimeEncryptionConfig completed = builder.build();

        assertThatThrownBy(() -> builder.x509Certificate((X509Certificate) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("x509Certificate is required");

        assertThat(builder.getX509Certificate()).isSameAs(original);
        assertThat(builder.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(builder.getCipherAlgorithm()).isEqualTo("AES256_CBC");
        assertThat(builder.build().getX509Certificate()).isSameAs(original);
        assertThat(completed.getX509Certificate()).isSameAs(original);
        assertThat(completed.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(completed.getCipherAlgorithm()).isEqualTo("AES256_CBC");
        verifyNoInteractions(original);
    }

    @Test
    void validSigningReplacementKeepsPreferencesAndDoesNotChangeCompletedValue() {
        final Pkcs12Config original = store((byte) 1);
        final Pkcs12Config replacement = store((byte) 2);
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder()
                .pkcs12Config(original).signatureAlgorithm("SHA384withRSA");
        final SmimeSigningConfig completed = builder.build();

        assertThat(builder.pkcs12Config(replacement)).isSameAs(builder);

        assertThat(builder.getPkcs12Config()).isSameAs(replacement);
        assertThat(builder.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
        assertThat(builder.build().getPkcs12Config()).isSameAs(replacement);
        assertThat(completed.getPkcs12Config()).isSameAs(original);
    }

    @Test
    void validEncryptionReplacementKeepsPreferencesAndDoesNotChangeCompletedValue() {
        final X509Certificate original = mock(X509Certificate.class);
        final X509Certificate replacement = mock(X509Certificate.class);
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder()
                .x509Certificate(original).keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC");
        final SmimeEncryptionConfig completed = builder.build();

        assertThat(builder.x509Certificate(replacement)).isSameAs(builder);

        assertThat(builder.getX509Certificate()).isSameAs(replacement);
        assertThat(builder.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(builder.getCipherAlgorithm()).isEqualTo("AES256_CBC");
        assertThat(builder.build().getX509Certificate()).isSameAs(replacement);
        assertThat(completed.getX509Certificate()).isSameAs(original);
        verifyNoInteractions(original, replacement);
    }

    @Test
    void optionalAlgorithmNullsStillLeaveSelectionToTheModule() {
        final Pkcs12Config material = store((byte) 1);
        final X509Certificate certificate = mock(X509Certificate.class);
        final SmimeSigningConfig signing = SmimeSigningConfig.builder().pkcs12Config(material)
                .signatureAlgorithm("SHA384withRSA").signatureAlgorithm(null).build();
        final SmimeEncryptionConfig encryption = SmimeEncryptionConfig.builder().x509Certificate(certificate)
                .keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC")
                .keyEncapsulationAlgorithm(null).cipherAlgorithm(null).build();

        assertThat(signing.getPkcs12Config()).isSameAs(material);
        assertThat(signing.getSignatureAlgorithm()).isNull();
        assertThat(encryption.getX509Certificate()).isSameAs(certificate);
        assertThat(encryption.getKeyEncapsulationAlgorithm()).isNull();
        assertThat(encryption.getCipherAlgorithm()).isNull();
        verifyNoInteractions(certificate);
    }

    @Test
    void incompleteBuildersContinueRejectingMissingMaterialAtConstruction() {
        assertThatThrownBy(() -> SmimeSigningConfig.builder().build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SmimeEncryptionConfig.builder().build()).isInstanceOf(NullPointerException.class);
    }

    private static Pkcs12Config store(final byte marker) {
        // A synthetic, unparsed store proves that configuration still performs no S/MIME cryptography.
        return Pkcs12Config.builder().pkcs12Store(new byte[] {marker}).storePassword("store")
                .keyAlias("mail").keyPassword("key").build();
    }
}
