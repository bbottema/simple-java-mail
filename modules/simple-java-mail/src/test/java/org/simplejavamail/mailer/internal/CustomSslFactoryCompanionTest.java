package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;

import javax.net.ssl.SSLSocketFactory;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomSslFactoryCompanionTest {

    @Test
    void instanceInspectionPreservesIdentityAndIndependentClearingRetainsTheClassChoice() throws Exception {
        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final MailerRegularBuilder<?> builder = builder().withCustomSSLFactoryClass("javax.net.ssl.SSLSocketFactory")
                .withCustomSSLFactoryInstance(factory);
        assertThat(builder.getCustomSSLFactoryInstance()).isSameAs(factory);
        try (Mailer original = builder.buildMailer()) {
            assertThat(original.getServerConfig().getCustomSSLFactoryInstance()).isSameAs(factory);
            assertThat(builder.clearCustomSSLFactoryInstance()).isSameAs(builder);
            assertThat(builder.clearCustomSSLFactoryInstance()).isSameAs(builder);
            assertThat(builder.getCustomSSLFactoryInstance()).isNull();
            assertThat(builder.getCustomSSLFactory()).isEqualTo("javax.net.ssl.SSLSocketFactory");
            try (Mailer classOnly = builder.buildMailer()) {
                assertThat(classOnly.getServerConfig().getCustomSSLFactoryInstance()).isNull();
                assertThat(classOnly.getServerConfig().getCustomSSLFactoryClass()).isEqualTo("javax.net.ssl.SSLSocketFactory");
            }
            assertThat(original.getServerConfig().getCustomSSLFactoryInstance()).isSameAs(factory);
        }
    }

    @Test
    void clearingTheClassLeavesTheInstanceInPlace() throws Exception {
        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final MailerRegularBuilder<?> builder = builder().withCustomSSLFactoryClass("javax.net.ssl.SSLSocketFactory")
                .withCustomSSLFactoryInstance(factory);
        assertThat(builder.clearCustomSSLFactoryClass()).isSameAs(builder);
        assertThat(builder.clearCustomSSLFactoryClass()).isSameAs(builder);
        assertThat(builder.getCustomSSLFactory()).isNull();
        assertThat(builder.getCustomSSLFactoryInstance()).isSameAs(factory);
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getServerConfig().getCustomSSLFactoryInstance()).isSameAs(factory);
            assertThat(mailer.getServerConfig().getCustomSSLFactoryClass()).isNull();
        }
    }

    @Test
    void classClearingDoesNotRestoreOrdinaryPropertiesAndStillRespectsLocks() throws Exception {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "relay.example.org",
                "simplejavamail.custom.sslfactory.class", "javax.net.ssl.SSLSocketFactory")).load());
        final MailerRegularBuilder<?> builder = configured.mailerBuilder().withTransportModeLoggingOnly(true);
        assertThat(builder.getCustomSSLFactory()).isEqualTo("javax.net.ssl.SSLSocketFactory");
        builder.clearCustomSSLFactoryClass();
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getServerConfig().getCustomSSLFactoryClass()).isNull();
        }

        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "relay.example.org",
                "simplejavamail.locked.custom.sslfactory.class", "javax.net.ssl.SSLSocketFactory")).load());
        final MailerRegularBuilder<?> lockedBuilder = locked.mailerBuilder().withTransportModeLoggingOnly(true);
        lockedBuilder.clearCustomSSLFactoryClass();
        assertThatThrownBy(lockedBuilder::buildMailer).hasMessageContaining("locked.custom.sslfactory.class");
    }

    private static MailerRegularBuilder<?> builder() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder()
                .withSMTPServerHost("relay.example.org").withTransportModeLoggingOnly(true);
    }
}
