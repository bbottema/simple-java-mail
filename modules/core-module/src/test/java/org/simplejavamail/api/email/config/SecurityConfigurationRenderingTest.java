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
}
