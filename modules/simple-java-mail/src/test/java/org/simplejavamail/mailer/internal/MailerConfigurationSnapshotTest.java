package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.TlsConfig;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;
import java.util.Properties;

import static testutil.MailerTestConfig.updateMailer;
import static org.assertj.core.api.Assertions.assertThat;

class MailerConfigurationSnapshotTest {

    @Test
    void automaticRenderingRedactsSensitiveExtraPropertiesWithoutChangingExplicitAccess() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.password", "smtp-secret-value");
        properties.setProperty("mail.smtp.auth.oauth2.access-token", "oauth-secret-value");
        properties.setProperty("vendor.API_KEY", "api-secret-value");
        properties.setProperty("vendor.private-key", "private-secret-value");
        properties.setProperty("mail.smtp.localhost", "public-client.example.org");
        final MailerRegularBuilder<?> builder = builder().withProperties(properties);

        assertThat(builder.toString()).doesNotContain("smtp-secret-value", "oauth-secret-value", "api-secret-value", "private-secret-value");
        try (Mailer mailer = builder.buildMailer()) {
            final OperationalConfig config = mailer.getOperationalConfig();
            assertThat(config.toString()).contains("mail.smtp.password", "<redacted>", "public-client.example.org")
                    .doesNotContain("smtp-secret-value", "oauth-secret-value", "api-secret-value", "private-secret-value");
            assertThat(mailer.toString()).contains("mail.smtp.password", "<redacted>", "public-client.example.org")
                    .doesNotContain("smtp-secret-value", "oauth-secret-value", "api-secret-value", "private-secret-value");
            assertThat(config.getProperties().getProperty("mail.smtp.password")).isEqualTo("smtp-secret-value");
            assertThat(mailer.getSession().getProperty("mail.smtp.password")).isEqualTo("smtp-secret-value");
        }
    }

    @Test
    void sensitiveProviderObjectsAreNotRenderedBeforeRedaction() throws Exception {
        final Object secret = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Sensitive objects must not be rendered");
            }
        };
        final Properties properties = new Properties();
        properties.put("vendor.credentials", secret);
        try (Mailer mailer = builder().withProperties(properties).buildMailer()) {
            assertThat(mailer.getOperationalConfig().toString()).contains("vendor.credentials", "<redacted>");
            assertThat(mailer.toString()).contains("vendor.credentials", "<redacted>");
            assertThat(mailer.getOperationalConfig().getProperties().get("vendor.credentials")).isSameAs(secret);
        }
    }

    @Test
    void reusingBuilderCannotChangeAnExistingOperationalSnapshot() throws Exception {
        final MailerRegularBuilder<?> builder = builder().withProperty("vendor.route", "first");
        try (Mailer first = builder.buildMailer()) {
            builder.withProperty("vendor.route", "second");
            try (Mailer second = builder.buildMailer()) {
                assertThat(first.getOperationalConfig().getProperties().getProperty("vendor.route")).isEqualTo("first");
                assertThat(first.getSession().getProperty("vendor.route")).isEqualTo("first");
                assertThat(second.getOperationalConfig().getProperties().getProperty("vendor.route")).isEqualTo("second");
            }
        }
    }

    @Test
    void propertyInspectionReturnsIndependentContainersAndPreservesSuppliedObjectIdentity() throws Exception {
        final Object providerObject = new Object();
        final Properties source = new Properties();
        source.put("vendor.factory", providerObject);
        source.setProperty("vendor.route", "original");
        final MailerRegularBuilder<?> builder = builder().withProperties(source);
        source.setProperty("vendor.route", "source changed");
        builder.getProperties().setProperty("vendor.route", "getter changed");

        assertThat(builder.getProperties().getProperty("vendor.route")).isEqualTo("original");
        assertThat(builder.getProperties().get("vendor.factory")).isSameAs(providerObject);
        try (Mailer mailer = builder.buildMailer()) {
            mailer.getOperationalConfig().getProperties().setProperty("vendor.route", "snapshot getter changed");
            assertThat(mailer.getOperationalConfig().getProperties().getProperty("vendor.route")).isEqualTo("original");
            assertThat(mailer.getOperationalConfig().getProperties().get("vendor.factory")).isSameAs(providerObject);
            assertThat(mailer.getSession().getProperty("vendor.route")).isEqualTo("original");
            assertThat(builder.getProperties().getProperty("vendor.route")).isEqualTo("original");
        }
    }

    @Test
    void trustedHostInputAndInspectionDoNotMutateStoredChoices() throws Exception {
        final String[] hosts = {"first.example.org", "second.example.org"};
        final MailerRegularBuilder<?> builder = updateMailer(builder(), fixture -> fixture.withTlsConfig(fixture.getTlsConfig().toBuilder().trustingSSLHosts(hosts).build()));
        hosts[0] = "source-change.example.org";
        assertThat(builder.getTlsConfig().getSslHostsToTrust()).containsExactly("first.example.org", "second.example.org");
        assertInspectionCannotChangeStoredHosts(builder.getTlsConfig().getSslHostsToTrust(), builder);

        try (Mailer mailer = builder.buildMailer()) {
            builder.withTlsConfig(builder.getTlsConfig().toBuilder().trustingSSLHosts("replacement.example.org").build());
            assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).containsExactly("first.example.org", "second.example.org");
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo("first.example.org second.example.org");
        }
    }

    private static void assertInspectionCannotChangeStoredHosts(final List<String> inspected, final MailerRegularBuilder<?> builder) {
        try {
            inspected.set(0, "inspection-change.example.org");
        } catch (UnsupportedOperationException immutableInspection) {
            // An immutable view and a detached mutable copy both protect the stored choice.
        }
        assertThat(builder.getTlsConfig().getSslHostsToTrust()).containsExactly("first.example.org", "second.example.org");
    }

    private static MailerRegularBuilder<?> builder() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder()
                .withSMTPServerHost("relay.example.org").withTransportModeLoggingOnly(true);
    }
}
