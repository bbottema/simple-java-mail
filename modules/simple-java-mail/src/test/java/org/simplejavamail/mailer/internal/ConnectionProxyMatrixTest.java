package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProxyMatrixTest {
    @Test
    void completeRawEndpointAndTypedMemberMatrix() {
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            for (boolean supplied : new boolean[] {false, true}) {
                for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                    for (int source = 0; source < 3; source++) {
                        if (!supplied && source == 2) { continue; }
                        for (String rawHost : new String[] {null, "raw.example.org", "raw.example.org:1081"}) {
                            for (String rawPort : new String[] {null, "1082", "0x43a"}) {
                                for (int host = 0; host < 4; host++) {
                                    for (int port = 0; port < 4; port++) {
                                        verify(strategy, supplied, policy, source, rawHost, rawPort, host, port);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(final TransportStrategy strategy, final boolean supplied, final ConnectionConflictPolicy policy,
            final int source, final String rawHost, final String rawPort, final int hostChoice, final int portChoice) {
        final String importedHost = rawHost == null ? null : "raw.example.org";
        final Integer importedPort = rawPort != null ? Integer.valueOf(1082) : rawHost != null && rawHost.endsWith(":1081") ? Integer.valueOf(1081) : null;
        final String selectedHost = hostChoice == 0 ? importedHost : hostChoice == 1 ? "raw.example.org" : hostChoice == 2 ? "typed.example.org" : null;
        final Integer selectedPort = portChoice == 1 ? Integer.valueOf(1082) : portChoice == 2 ? Integer.valueOf(1084) : portChoice == 3 ? null
                : importedPort != null ? importedPort : !supplied || hostChoice != 0 || selectedHost != null ? Integer.valueOf(1080) : null;
        final boolean conflict = importedHost != null && hostChoice != 0 && !Objects.equals(importedHost, selectedHost)
                || importedPort != null && portChoice != 0 && !Objects.equals(importedPort, selectedPort);
        final boolean rejects = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && conflict || selectedHost != null && selectedPort == null;
        final Properties properties = new Properties();
        if (rawHost != null) { properties.setProperty(strategy.propertyNameSocksHost(), rawHost); }
        if (rawPort != null) { properties.setProperty(strategy.propertyNameSocksPort(), rawPort); }
        final Map<String, String> captured = new java.util.LinkedHashMap<>();
        if (source == 0) { properties.forEach((key, value) -> captured.put("simplejavamail.extraproperties." + key, value.toString())); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(captured).load());
        final Properties sessionProperties = new Properties();
        sessionProperties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (source == 2) { sessionProperties.putAll(properties); }
        final Session session = supplied ? Session.getInstance(sessionProperties) : null;
        final MailerGenericBuilder<?> builder = supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy);
        if (source == 1) { builder.withProperties(properties); }
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder();
        if (hostChoice != 0) { choices.withHost(selectedHost); }
        if (portChoice != 0) { choices.withPort(selectedPort); }
        builder.withConnectionConflictPolicy(policy).withProxyConfig(choices.build());
        MailerSessionSettings actual = null;
        RuntimeException failure = null;
        try { actual = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session); }
        catch (RuntimeException rejected) { failure = rejected; }
        final String context = strategy + " supplied=" + supplied + " " + policy + " source=" + source
                + " rawHost=" + rawHost + " rawPort=" + rawPort + " host=" + hostChoice + " port=" + portChoice;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(actual.getProxyConfig().getRemoteProxyHost()).as(context).isEqualTo(selectedHost);
            assertThat(actual.getProxyConfig().getRemoteProxyPort()).as(context).isEqualTo(selectedPort);
            if (supplied && hostChoice == 0 && portChoice == 0) {
                assertThat(actual.copyEffectiveProperties().getProperty(strategy.propertyNameSocksPort())).as(context).isEqualTo(rawPort);
            }
        }
    }
}
