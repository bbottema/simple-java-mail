package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProxyCredentialsMatrixTest {
    @Test
    void allCredentialPresenceReplacementAndRemovalCombinations() {
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            for (boolean supplied : new boolean[] {false, true}) {
                for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                    for (int configuredUser = 0; configuredUser < 3; configuredUser++) {
                        for (int configuredPassword = 0; configuredPassword < 3; configuredPassword++) {
                            for (int localUser = 0; localUser < 4; localUser++) {
                                for (int localPassword = 0; localPassword < 4; localPassword++) {
                                    verify(strategy, supplied, policy, configuredUser, configuredPassword, localUser, localPassword);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(final TransportStrategy strategy, final boolean supplied, final ConnectionConflictPolicy policy,
            final int configuredUser, final int configuredPassword, final int localUser, final int localPassword) {
        final int user = localUser == 0 ? configuredUser : localUser == 3 ? 0 : localUser;
        final int password = localPassword == 0 ? configuredPassword : localPassword == 3 ? 0 : localPassword;
        final boolean conflict = configuredUser != 0 && localUser != 0 && configuredUser != user
                || configuredPassword != 0 && localPassword != 0 && configuredPassword != password;
        final boolean rejects = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && conflict || (user == 0) != (password == 0);
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (configuredUser != 0) { properties.put("simplejavamail.proxy.username", value("user", configuredUser)); }
        if (configuredPassword != 0) { properties.put("simplejavamail.proxy.password", value("test-password", configuredPassword)); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
        final Properties endpoint = new Properties();
        endpoint.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        endpoint.setProperty(strategy.propertyNameSocksHost(), "proxy.example.org");
        endpoint.setProperty(strategy.propertyNameSocksPort(), "1081");
        final Session session = supplied ? Session.getInstance(endpoint) : null;
        final MailerGenericBuilder<?> builder = (supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy).withProperties(endpoint))
                .withConnectionConflictPolicy(policy);
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder();
        if (localUser != 0) { choices.withUsername(value("user", user)); }
        if (localPassword != 0) { choices.withPassword(value("test-password", password)); }
        builder.withProxyConfig(choices.build());
        RuntimeException failure = null;
        MailerSessionSettings settings = null;
        try { settings = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session); }
        catch (RuntimeException rejected) { failure = rejected; }
        final String context = strategy + " supplied=" + supplied + " " + policy + " configured=" + configuredUser + "/" + configuredPassword
                + " local=" + localUser + "/" + localPassword;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(settings.getProxyConfig().getUsername()).isEqualTo(value("user", user));
            assertThat(settings.getProxyConfig().getPassword()).isEqualTo(value("test-password", password));
            assertThat(settings.getProxyConfig().requiresAuthentication()).isEqualTo(user != 0);
            assertThat(settings.getProxyConfig().getRemoteProxyPort()).isEqualTo(1081);
        }
    }

    private static String value(final String prefix, final int value) { return value == 0 ? null : prefix + value; }
}
