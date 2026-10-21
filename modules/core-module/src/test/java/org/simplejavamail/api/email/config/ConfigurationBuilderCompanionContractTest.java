package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

class ConfigurationBuilderCompanionContractTest {

    @Test
    void smtpDsnComponentInspectionDoesNotValidateUnfinishedChoices() {
        final SmtpDeliveryOptions.Builder builder = SmtpDeliveryOptions.builder().fixingEnvelopeId("")
                .withDeliveryStatusNotification(HEADERS_ONLY, NEVER, FAILURE);
        assertThat(builder.getDeliveryStatusNotificationReturnOption()).isEqualTo(HEADERS_ONLY);
        final Set<DeliveryStatusNotification.NotifyOption> events = builder.getDeliveryStatusNotificationNotifyOptions();
        assertThat(events).containsExactly(NEVER, FAILURE);
        events.clear();
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER, FAILURE);
        assertThat(builder.getEnvelopeId()).isNull();
        builder.fixingEnvelopeId("");
        assertThat(builder.getEnvelopeId()).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dsnInspectionDoesNotBuildOrValidateAnIncompleteRequest() {
        final DeliveryStatusNotification.DeliveryStatusNotificationBuilder builder = DeliveryStatusNotification.builder();
        assertThat(builder.getReturnOption()).isNull();
        assertThat(builder.getNotifyOptions()).isEmpty();
        assertThat(builder.getEnvelopeId()).isNull();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);

        builder.notifyOptions(NEVER, FAILURE).envelopeId("");
        assertThat(builder.getNotifyOptions()).containsExactly(NEVER, FAILURE);
        assertThat(builder.getEnvelopeId()).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dsnGettersAndClearsPreserveOtherChoicesAndCompletedRequests() {
        final DeliveryStatusNotification.DeliveryStatusNotificationBuilder builder = DeliveryStatusNotification.builder()
                .returnOption(HEADERS_ONLY).notifyOptions(FAILURE).envelopeId("fixed-attempt");
        final DeliveryStatusNotification original = builder.build();
        final Set<DeliveryStatusNotification.NotifyOption> inspected = builder.getNotifyOptions();
        inspected.clear();
        inspected.add(NEVER);
        assertThat(builder.getNotifyOptions()).containsExactly(FAILURE);

        assertThat(builder.clearNotifyOptions()).isSameAs(builder);
        builder.clearNotifyOptions();
        assertThat(builder.getNotifyOptions()).isEmpty();
        assertThat(builder.getReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(builder.getEnvelopeId()).isEqualTo("fixed-attempt");

        assertThat(builder.clearEnvelopeId()).isSameAs(builder);
        builder.clearEnvelopeId();
        assertThat(builder.getEnvelopeId()).isNull();
        assertThat(builder.getReturnOption()).isEqualTo(HEADERS_ONLY);
        builder.clearReturnOption();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(original.getNotifyOptions()).containsExactly(FAILURE);
        assertThat(original.getEnvelopeId()).isEqualTo("fixed-attempt");
    }

    @Test
    void dsnClearsRemoveOnlyTheNamedChoice() {
        final DeliveryStatusNotification.DeliveryStatusNotificationBuilder builder = DeliveryStatusNotification.builder()
                .returnOption(HEADERS_ONLY).notifyOptions(FAILURE).envelopeId("fixed-attempt");
        builder.clearNotifyOptions().clearNotifyOptions();
        assertThat(builder.getNotifyOptions()).isEmpty();
        assertThat(builder.getEnvelopeId()).isEqualTo("fixed-attempt");
        builder.notifyOptions(FAILURE).clearEnvelopeId().clearEnvelopeId();
        assertThat(builder.getEnvelopeId()).isNull();
        assertThat(builder.getNotifyOptions()).containsExactly(FAILURE);
        assertThat(builder.getReturnOption()).isEqualTo(HEADERS_ONLY);
    }

    @Test
    void pkcs12InspectionWorksBeforeAnyRequiredMaterialIsSupplied() {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder();
        assertThat(builder.getPkcs12StoreData()).isNull();
        assertThat(builder.getStorePassword()).isNull();
        assertThat(builder.getKeyAlias()).isNull();
        assertThat(builder.getKeyPassword()).isNull();

        builder.pkcs12Store(new byte[0]);
        assertThat(builder.getPkcs12StoreData()).isEmpty();
        assertThat(builder.build().getPkcs12StoreData()).isEmpty();
    }

    @Test
    void pkcs12BuilderInspectionDetachesStoreAndPasswordArrays() {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder().pkcs12Store(new byte[] {1, 2})
                .storePassword("store-secret").keyAlias("mail").keyPassword("key-secret");
        final Pkcs12Config original = builder.build();
        builder.getPkcs12StoreData()[0] = 99;
        builder.getStorePassword()[0] = 'x';
        builder.getKeyPassword()[0] = 'x';
        assertThat(builder.getPkcs12StoreData()).containsExactly(1, 2);
        assertThat(builder.getStorePassword()).isEqualTo("store-secret".toCharArray());
        assertThat(builder.getKeyAlias()).isEqualTo("mail");
        assertThat(builder.getKeyPassword()).isEqualTo("key-secret".toCharArray());
        assertThat(original.getStorePassword()).isEqualTo("store-secret".toCharArray());
        assertThat(original.getKeyPassword()).isEqualTo("key-secret".toCharArray());
    }

    @Test
    void signingInspectionRetainsAbsenceAndThePublishedHashDefault() {
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder builder = OpenPgpSigningConfig.builder();
        assertThat(builder.getSecretKeyRing()).isNull();
        assertThat(builder.getPassphrase()).isEmpty();
        assertThat(builder.getSigningKeyId()).isNull();
        assertThat(builder.getHashAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM);
        builder.secretKeyRing(new byte[0]);
        assertThat(builder.getSecretKeyRing()).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signingInspectionCopiesSecretsAndPreservesBuiltValues() {
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder builder = OpenPgpSigningConfig.builder()
                .secretKeyRing(new byte[] {3, 4}).passphrase("signing-secret").signingKeyId(42)
                .hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        final OpenPgpSigningConfig original = builder.build();
        builder.getSecretKeyRing()[0] = 99;
        builder.getPassphrase()[0] = 'x';
        assertThat(builder.getSecretKeyRing()).containsExactly(3, 4);
        assertThat(builder.getPassphrase()).isEqualTo("signing-secret".toCharArray());
        assertThat(builder.getSigningKeyId()).isEqualTo(42L);
        assertThat(builder.getHashAlgorithm()).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        builder.clearSigningKeyId().resetHashAlgorithm();
        assertThat(builder.getSigningKeyId()).isNull();
        assertThat(builder.getHashAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM);
        assertThat(original.getSigningKeyId()).isEqualTo(42L);
        assertThat(original.getPassphrase()).isEqualTo("signing-secret".toCharArray());
    }

    @Test
    void encryptionInspectionDoesNotValidateEmptyStoredKeyMaterial() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = OpenPgpEncryptionConfig.builder();
        assertThat(builder.getRecipientPublicKeyRings()).isEmpty();
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM);
        builder.addRecipientPublicKeyRing(new byte[0]);
        assertThat(builder.getRecipientPublicKeyRings()).hasSize(1);
        assertThat(builder.getRecipientPublicKeyRings().get(0)).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptionInspectionCopiesCollectionsAndNestedArrays() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(new byte[] {5, 6}).symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        final OpenPgpEncryptionConfig original = builder.build();
        final List<byte[]> inspected = builder.getRecipientPublicKeyRings();
        inspected.get(0)[0] = 99;
        inspected.clear();
        assertThat(builder.getRecipientPublicKeyRings()).hasSize(1);
        assertThat(builder.getRecipientPublicKeyRings().get(0)).containsExactly(5, 6);
        assertThat(builder.clearRecipientPublicKeyRings()).isSameAs(builder);
        builder.clearRecipientPublicKeyRings();
        assertThat(builder.getRecipientPublicKeyRings()).isEmpty();
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(original.getRecipientPublicKeyRings().get(0)).containsExactly(5, 6);
        builder.resetSymmetricAlgorithm();
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM);
    }

    @Test
    void encryptionKeyClearKeepsTheCipherAndPreviousSnapshot() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(new byte[] {5, 6}).symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        final OpenPgpEncryptionConfig original = builder.build();
        assertThat(builder.clearRecipientPublicKeyRings()).isSameAs(builder);
        builder.clearRecipientPublicKeyRings();
        assertThat(builder.getRecipientPublicKeyRings()).isEmpty();
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        assertThat(original.getRecipientPublicKeyRings().get(0)).containsExactly(5, 6);
    }

    @Test
    void receiveClearsRemoveOnlyTheirNamedChoices() {
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> new char[] {'s'};
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[] {1}).addDecryptionKeyRing(new byte[] {2}, "secret").passphraseProvider(provider);
        builder.clearVerificationKeyRings();
        assertThat(builder.getVerificationKeyRings()).isEmpty();
        assertThat(builder.getDecryptionKeyRings()).hasSize(1);
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);
        builder.addVerificationKeyRing(new byte[] {1}).clearDecryptionKeyRings();
        assertThat(builder.getDecryptionKeyRings()).isEmpty();
        assertThat(builder.getVerificationKeyRings()).hasSize(1);
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);
        builder.addDecryptionKeyRing(new byte[] {2}, "secret").clearPassphraseProvider();
        assertThat(builder.getPassphraseProvider()).isNull();
        assertThat(builder.getVerificationKeyRings()).hasSize(1);
        assertThat(builder.getDecryptionKeyRings()).hasSize(1);
    }

    @Test
    void receiveInspectionDoesNotValidateUnfinishedVerificationMaterialOrInvokeCallbacks() {
        final AtomicInteger callbackCalls = new AtomicInteger();
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> {
            callbackCalls.incrementAndGet();
            return new char[] {'s'};
        };
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder();
        assertThat(builder.getVerificationKeyRings()).isEmpty();
        assertThat(builder.getDecryptionKeyRings()).isEmpty();
        assertThat(builder.getPassphraseProvider()).isNull();
        builder.addVerificationKeyRing(new byte[0]).passphraseProvider(provider);
        assertThat(builder.getVerificationKeyRings().get(0)).isEmpty();
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);
        assertThat(callbackCalls).hasValue(0);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiveClearsAreIndependentAndDoNotWipeEarlierSnapshots() {
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> new char[] {'s'};
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[] {7, 8}).addDecryptionKeyRing(new byte[] {9, 10}, "receive-secret")
                .passphraseProvider(provider);
        final OpenPgpReceiveConfig original = builder.build();
        final List<byte[]> verification = builder.getVerificationKeyRings();
        verification.get(0)[0] = 99;
        verification.clear();
        final List<OpenPgpReceiveConfig.SecretKeyRing> decryption = builder.getDecryptionKeyRings();
        decryption.get(0).getKeyRing()[0] = 99;
        decryption.get(0).getPassphrase()[0] = 'x';
        decryption.clear();
        assertThat(builder.getVerificationKeyRings().get(0)).containsExactly(7, 8);
        assertThat(builder.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(9, 10);
        assertThat(builder.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("receive-secret".toCharArray());

        assertThat(builder.clearVerificationKeyRings()).isSameAs(builder);
        builder.clearVerificationKeyRings();
        assertThat(builder.getVerificationKeyRings()).isEmpty();
        assertThat(builder.getDecryptionKeyRings()).hasSize(1);
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);

        assertThat(builder.clearDecryptionKeyRings()).isSameAs(builder);
        builder.clearDecryptionKeyRings();
        assertThat(builder.getDecryptionKeyRings()).isEmpty();
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);

        assertThat(builder.clearPassphraseProvider()).isSameAs(builder);
        builder.clearPassphraseProvider();
        assertThat(builder.getPassphraseProvider()).isNull();
        assertThat(original.getVerificationKeyRings().get(0)).containsExactly(7, 8);
        assertThat(original.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(9, 10);
        assertThat(original.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("receive-secret".toCharArray());
        assertThat(original.getPassphraseProvider()).isSameAs(provider);
        assertThat(builder.build().getDecryptionKeyRings()).isEmpty();
    }
}
