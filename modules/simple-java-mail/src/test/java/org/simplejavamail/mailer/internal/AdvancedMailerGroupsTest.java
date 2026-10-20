package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvancedMailerGroupsTest {
    @Test
    void explicitEmptyProxyStringsDoNotRestoreCapturedEndpointOrCredentials() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.proxy.host", "proxy.example",
                "simplejavamail.proxy.username", "user", "simplejavamail.proxy.password", "captured-secret"));
        try (Mailer inherited = factory.mailerBuilder().withProxyConfig(SocksProxyConfig.builder().build()).buildMailer()) {
            assertThat(inherited.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example");
            assertThat(inherited.getProxyConfig().getPassword()).isEqualTo("captured-secret");
        }
        for (SocksProxyConfig choices : List.of(SocksProxyConfig.builder().withHost("").build(),
                SocksProxyConfig.builder().withPassword("").build())) {
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory.mailerBuilder().withProxyConfig(choices).buildMailer()) {
                    // Close an unexpectedly successful construction as well.
                }
            }).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void explicitEmptyConnectionStringsRemoveSettingsInsteadOfRestoringCapturedValues() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.smtp.clienthostname", "captured.example",
                "simplejavamail.smtp.localaddress", "192.0.2.7"));
        try (Mailer inherited = factory.mailerBuilder().withConnectionConfig(ConnectionConfig.builder().build()).buildMailer()) {
            assertThat(inherited.getSession().getProperty("mail.smtp.localhost")).isEqualTo("captured.example");
            assertThat(inherited.getSession().getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.7");
        }
        try (Mailer cleared = factory.mailerBuilder().withConnectionConfig(ConnectionConfig.builder()
                .withSmtpClientHostname("").withLocalBindAddress("").build()).buildMailer()) {
            assertThat(cleared.getSession().getProperty("mail.smtp.localhost")).isNull();
            assertThat(cleared.getSession().getProperty("mail.smtp.localaddress")).isNull();
        }
    }

    @Test
    void explicitEmptySslFactoryClassDoesNotRestoreCapturedClass() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.custom.sslfactory.class", "captured.Factory"));
        try (Mailer inherited = factory.mailerBuilder().withTlsConfig(TlsConfig.builder().build()).buildMailer()) {
            assertThat(inherited.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isEqualTo("captured.Factory");
        }
        try (Mailer cleared = factory.mailerBuilder()
                .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryClass("").build()).buildMailer()) {
            assertThat(cleared.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isEmpty();
        }
    }

    @Test
    void replacingGroupRestoresCapturedMembersAndDoesNotChangeEarlierMailer() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.defaults.connectionpool.maxsize", 7))
                .mailerBuilder().withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize(9)
                        .withClaimTimeout(Duration.ofSeconds(2)).build());
        try (Mailer first = builder.buildMailer();
             Mailer replaced = builder.withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(1).build()).buildMailer()) {
            assertThat(first.getOperationalConfig().getConnectionPoolMaxSize()).isEqualTo(9);
            assertThat(replaced.getOperationalConfig().getConnectionPoolMaxSize()).isEqualTo(7);
            assertThat(builder.getConnectionPoolConfig().getMaxSize()).isNull();
            assertThat(builder.getConnectionPoolConfig().getClaimTimeout()).isNull();
        }
    }

    @Test
    void replacingConnectionGroupDiscardsExplicitSessionProvenance() throws Exception {
        final Properties properties = new Properties();
        properties.put("mail.smtp.timeout", "4321");
        final Session session = Session.getInstance(properties);
        try (Mailer mailer = factory(Map.of("simplejavamail.defaults.sessiontimeoutmillis", 999))
                .mailerBuilder(session)
                .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(3)).build())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("client.example").build()).buildMailer()) {
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("4321");
            assertThat(session.getProperty("mail.smtp.localhost")).isEqualTo("client.example");
        }
    }

    @Test
    void groupConstructionIsDetachedAndDoesNotUseBorrowedExecutor() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final AsyncExecutionConfig supplied = AsyncExecutionConfig.builder().withExecutorService(executor).build();
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withAsyncExecutionConfig(supplied).buildMailer()) {
                assertThat(mailer.getOperationalConfig().getExecutorService()).isSameAs(executor);
            }
            assertThat(executor.isShutdown()).isFalse();
            final SocksProxyConfig.Builder proxy = SocksProxyConfig.builder().withHost("proxy.example").withPassword("secret");
            final SocksProxyConfig snapshot = proxy.build();
            proxy.withHost("other.example");
            assertThat(snapshot.getHost()).isEqualTo("proxy.example");
            assertThat(snapshot.toString()).doesNotContain("secret");
            assertThat(proxy.toString()).doesNotContain("secret");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replacingConflictingGroupBeforeBuildDoesNotRetainItsLockConflict() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.locked.defaults.trustallhosts", false)).mailerBuilder()
                .withTlsConfig(TlsConfig.builder().trustingAllHosts(true).build());
        assertThatThrownBy(builder::buildMailer).hasMessageContaining("locked.defaults.trustallhosts");
        try (Mailer mailer = builder.withTlsConfig(TlsConfig.builder().build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
        }
    }

    @Test
    void asyncCapacityChoiceIsAtomicAndRejectsInvalidWait() {
        final AsyncExecutionConfig waiting = AsyncExecutionConfig.builder().withQueueCapacity(2)
                .waitForCapacity(Duration.ofSeconds(1)).build();
        assertThat(waiting.getCapacityWait()).isEqualTo(Duration.ofSeconds(1));
        assertThat(waiting.toBuilder().rejectWhenFull().build().getCapacityWait()).isNull();
        assertThatThrownBy(() -> AsyncExecutionConfig.builder().waitForCapacity(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tlsHostChoicesOwnInputArraysAndDetachBuilderInspection() {
        final String[] hosts = {"first.example"};
        final TlsConfig.Builder builder = TlsConfig.builder().trustingSSLHosts(hosts);
        final TlsConfig snapshot = builder.build();
        hosts[0] = "changed.example";
        final List<String> inspected = builder.getSslHostsToTrust();
        inspected.clear();
        builder.trustingSSLHosts("replacement.example");
        assertThat(snapshot.getSslHostsToTrust()).containsExactly("first.example");
        assertThatThrownBy(() -> snapshot.getSslHostsToTrust().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static SimpleJavaMail factory(final Map<String, ?> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.host", "localhost"))
                .withMap(properties).load());
    }
}
