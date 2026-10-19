package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationRenderingTest {
    @Test
    void dkimBuilderAndValueDoNotRenderPrivateKeyBytes() {
        final byte[] key = "synthetic-private-key".getBytes(StandardCharsets.UTF_8);
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder().dkimPrivateKeyData(key)
                .dkimSigningDomain("example.org").dkimSelector("mail");
        assertThat(builder.toString()).doesNotContain(Arrays.toString(key), "synthetic-private-key", "dkimPrivateKeyData");
        assertThat(builder.build().toString()).doesNotContain(Arrays.toString(key), "synthetic-private-key");
        assertThat(builder.build().getDkimPrivateKeyData()).containsExactly(key);
    }

    @Test
    void smimeSigningRenderingRetainsExistingSecretRedaction() {
        final Pkcs12Config keys = Pkcs12Config.builder().pkcs12Store(new byte[] {11, 22, 33})
                .storePassword("synthetic-store-secret").keyAlias("mail").keyPassword("synthetic-key-secret").build();
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder().pkcs12Config(keys);
        assertThat(builder.toString()).doesNotContain("synthetic-store-secret", "synthetic-key-secret", "[11, 22, 33]");
        assertThat(builder.build().toString()).doesNotContain("synthetic-store-secret", "synthetic-key-secret", "[11, 22, 33]");
    }

    @Test
    void explicitInspectionDoesNotExposeSecretsThroughAutomaticRendering() {
        final byte[] keyRing = "synthetic-key-ring".getBytes(StandardCharsets.UTF_8);
        final char[] passphrase = "synthetic-passphrase".toCharArray();
        final Pkcs12Config.Pkcs12ConfigBuilder store = Pkcs12Config.builder().pkcs12Store(keyRing)
                .storePassword(passphrase).keyAlias("mail").keyPassword(passphrase);
        final OpenPgpSigningConfig.OpenPgpSigningConfigBuilder signing = OpenPgpSigningConfig.builder()
                .secretKeyRing(keyRing).passphrase(passphrase);
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder encryption = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(keyRing);
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder receive = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(keyRing).addDecryptionKeyRing(keyRing, passphrase);

        assertThat(store.getPkcs12StoreData()).containsExactly(keyRing);
        assertThat(signing.getPassphrase()).containsExactly(passphrase);
        assertThat(receive.getDecryptionKeyRings().get(0).getPassphrase()).containsExactly(passphrase);
        for (Object configuration : new Object[] {store, store.build(), signing, signing.build(), encryption, encryption.build(),
                receive, receive.build(), receive.getDecryptionKeyRings().get(0)}) {
            assertThat(configuration.toString()).doesNotContain("synthetic-key-ring", "synthetic-passphrase", Arrays.toString(keyRing),
                    Arrays.toString(passphrase));
        }
    }
}
