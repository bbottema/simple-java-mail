package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BuilderInspectionContractTest {
    @Test
    void incompleteRetryBuildersCanBeInspectedWithoutBuilding() {
        final MailSendRetryPolicy.Builder builder = MailSendRetryPolicy.builder().withMaximumAttempts(3);
        assertThat(builder.getMaximumAttempts()).isEqualTo(3);
        assertThat(builder.getRetryDelay()).isNull();
        assertThat(builder.getMaximumRetryDelay()).isNull();
        assertThat(builder.build().getRetryDelay()).isNull();
        final MailSendRetryPolicy original = builder.withRetryDelay(Duration.ofSeconds(2)).withRetryBackoff(2, Duration.ofMinutes(1))
                .withWaitingCapacity(7).withMaximumMessageSize(512).withMemoryLimit(2048).withRetryJitter(0.2).build();
        assertThat(builder.getRetryDelay()).isEqualTo(original.getRetryDelay());
        assertThat(builder.getRetryBackoffMultiplier()).isEqualTo(2);
        assertThat(builder.getMaximumRetryDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(builder.getRetryJitter()).isEqualTo(0.2);
        assertThat(builder.getWaitingCapacity()).isEqualTo(7);
        assertThat(builder.getMaximumMessageSize()).isEqualTo(512);
        assertThat(builder.getMemoryLimit()).isEqualTo(2048);
        builder.clearRetrySchedule();
        assertThat(builder.getMaximumAttempts()).isNull();
        assertThat(builder.getRetryDelay()).isNull();
        assertThat(builder.getMaximumRetryDelay()).isNull();
        assertThat(original.isRetryEnabled()).isTrue();
    }

    @Test
    void failoverInspectionDoesNotResolveTheDerivedMaximum() {
        final FailoverPolicy.Builder builder = FailoverPolicy.builder().withPrimaryConnectionAttempts(2)
                .withPrimaryConnectionRetryDelay(Duration.ofSeconds(1)).withPrimaryRecheckInterval(Duration.ofSeconds(7))
                .withFailoverOnAuthenticationFailure(true).withFailoverOnCertificateVerificationFailure(true);
        assertThat(builder.getPrimaryConnectionAttempts()).isEqualTo(2);
        assertThat(builder.getPrimaryConnectionRetryDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(builder.getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(builder.getPrimaryMaximumRecheckInterval()).isNull();
        assertThat(builder.getPrimaryRecheckBackoffMultiplier()).isOne();
        assertThat(builder.isFailoverOnAuthenticationFailure()).isTrue();
        assertThat(builder.isFailoverOnCertificateVerificationFailure()).isTrue();
        assertThat(builder.build().getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofSeconds(7));
        builder.withPrimaryRecheckBackoff(2, Duration.ofMinutes(1));
        assertThat(builder.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofMinutes(1));
        builder.resetPrimaryRecheckBackoff();
        assertThat(builder.getPrimaryMaximumRecheckInterval()).isNull();
    }

    @Test
    void dkimClearsKeepKeysAndCompletedValuesWhileGettersDetachMutableChoices() {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder().dkimPrivateKeyData(new byte[] {11, 22, 33})
                .dkimSigningDomain("example.org").dkimSelector("mail").useLengthParam(false)
                .excludedHeadersFromDkimDefaultSigningList(new HashSet<>(Set.of("Date")))
                .headerCanonicalization(DkimConfig.Canonicalization.RELAXED).bodyCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                .signingAlgorithm("SHA256_WITH_RSA");
        final DkimConfig original = builder.build();
        builder.getDkimPrivateKeyData()[0] = 99;
        builder.getExcludedHeadersFromDkimDefaultSigningList().clear();
        assertThat(builder.getDkimPrivateKeyData()).containsExactly(11, 22, 33);
        assertThat(builder.getExcludedHeadersFromDkimDefaultSigningList()).containsExactly("Date");
        assertThat(builder.getUseLengthParam()).isFalse();
        assertThat(builder.getHeaderCanonicalization()).isEqualTo(DkimConfig.Canonicalization.RELAXED);
        assertThat(builder.getBodyCanonicalization()).isEqualTo(DkimConfig.Canonicalization.SIMPLE);
        assertThat(builder.getSigningAlgorithm()).isEqualTo("SHA256_WITH_RSA");
        assertThat(builder.clearUseLengthParam()).isSameAs(builder);
        builder.clearExcludedHeadersFromDkimDefaultSigningList().clearHeaderCanonicalization().clearBodyCanonicalization().clearSigningAlgorithm();
        assertThat(builder.getUseLengthParam()).isNull();
        assertThat(builder.getExcludedHeadersFromDkimDefaultSigningList()).isNull();
        assertThat(builder.getHeaderCanonicalization()).isNull();
        assertThat(builder.getBodyCanonicalization()).isNull();
        assertThat(builder.getSigningAlgorithm()).isNull();
        assertThat(builder.getDkimSigningDomain()).isEqualTo("example.org");
        assertThat(builder.getDkimSelector()).isEqualTo("mail");
        assertThat(original.getSigningAlgorithm()).isEqualTo("SHA256_WITH_RSA");
        assertThat(builder.build().getSigningAlgorithm()).isNull();
    }

    @Test
    void smimeClearsLeaveTheCertificateAndStoreIntact() {
        final Pkcs12Config store = Pkcs12Config.builder().pkcs12Store(new byte[] {1}).storePassword("store")
                .keyAlias("mail").keyPassword("key").build();
        final SmimeSigningConfig.SmimeSigningConfigBuilder signing = SmimeSigningConfig.builder().pkcs12Config(store)
                .signatureAlgorithm("SHA384withRSA");
        final SmimeSigningConfig originalSigning = signing.build();
        assertThat(signing.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
        assertThat(signing.clearSignatureAlgorithm()).isSameAs(signing);
        assertThat(signing.getSignatureAlgorithm()).isNull();
        assertThat(signing.getPkcs12Config()).isSameAs(store);
        assertThat(originalSigning.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
        final X509Certificate certificate = mock(X509Certificate.class);
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder encryption = SmimeEncryptionConfig.builder()
                .x509Certificate(certificate).cipherAlgorithm("AES256_CBC").keyEncapsulationAlgorithm("RSA_OAEP_SHA256");
        final SmimeEncryptionConfig originalEncryption = encryption.build();
        assertThat(encryption.getCipherAlgorithm()).isEqualTo("AES256_CBC");
        assertThat(encryption.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(encryption.clearCipherAlgorithm()).isSameAs(encryption);
        assertThat(encryption.clearKeyEncapsulationAlgorithm()).isSameAs(encryption);
        assertThat(encryption.getCipherAlgorithm()).isNull();
        assertThat(encryption.getKeyEncapsulationAlgorithm()).isNull();
        assertThat(encryption.getX509Certificate()).isSameAs(certificate);
        assertThat(originalEncryption.getCipherAlgorithm()).isEqualTo("AES256_CBC");
    }
}
