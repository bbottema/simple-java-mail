package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailStartingBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpConfigCompanionTest {

    @Test
    void clearingTheSigningKeyPreferenceRetainsMaterialAndOtherChoices() {
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder builder = OpenPgpSigningConfig.builder()
                .secretKeyRing(new byte[]{1, 2, 3})
                .passphrase("secret")
                .signingKeyId(42)
                .hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        final OpenPgpSigningConfig original = builder.build();

        assertThat(builder.clearSigningKeyId()).isSameAs(builder);
        final OpenPgpSigningConfig cleared = builder.clearSigningKeyId().build();

        assertThat(cleared.getSigningKeyId()).isNull();
        assertThat(cleared.getSecretKeyRing()).containsExactly(1, 2, 3);
        assertThat(cleared.getPassphrase()).containsExactly('s', 'e', 'c', 'r', 'e', 't');
        assertThat(cleared.getHashAlgorithm()).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        assertThat(original.getSigningKeyId()).isEqualTo(42L);
    }

    @Test
    void signingHashResetUsesTheCentralDefaultWithoutChangingTheKey() {
        assertThat(EmailStartingBuilder.DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA256);
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder builder = OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1})
                .signingKeyId(42).hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        final OpenPgpSigningConfig original = builder.build();

        assertThat(builder.resetHashAlgorithm()).isSameAs(builder);
        final OpenPgpSigningConfig reset = builder.resetHashAlgorithm().build();

        assertThat(reset.getHashAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM);
        assertThat(reset.getSigningKeyId()).isEqualTo(42L);
        assertThat(original.getHashAlgorithm()).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        assertThat(OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1}).build().getHashAlgorithm()).isEqualTo(reset.getHashAlgorithm());
    }

    @Test
    void encryptionResetUsesTheCentralDefaultWithoutChangingRecipients() {
        assertThat(EmailStartingBuilder.DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_256);
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(new byte[]{1, 2})
                .symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        final OpenPgpEncryptionConfig original = builder.build();

        assertThat(builder.resetSymmetricAlgorithm()).isSameAs(builder);
        final OpenPgpEncryptionConfig reset = builder.resetSymmetricAlgorithm().build();

        assertThat(reset.getSymmetricAlgorithm()).isEqualTo(EmailStartingBuilder.DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM);
        assertThat(reset.getRecipientPublicKeyRings()).hasSize(1);
        assertThat(reset.getRecipientPublicKeyRings().get(0)).containsExactly(1, 2);
        assertThat(original.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        assertThat(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{1}).build().getSymmetricAlgorithm())
                .isEqualTo(reset.getSymmetricAlgorithm());
    }
}
