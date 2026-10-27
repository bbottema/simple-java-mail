package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionSettingIdentityTest {
    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void configurationSessionRawAndTypedRoutesShareTheApplicableLogicalMember(final TransportStrategy strategy) {
        final String property = strategy.propertyNameTimeout();
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.readtimeoutmillis", 123,
                "simplejavamail.extraproperties." + property, "00123")).load();
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        properties.setProperty(property, "123");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(config).usingSession(session)
                .withProperty(property, "0x7b")
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build());

        final ResolvedConnection resolved = ConnectionConfigurationResolver.resolve(builder.captureConnectionInputs(session));
        final List<ConfigurationDeclaration<Object>> declarations = resolved.declarations.get(ConnectionSettings.projectedProperty(property));
        assertThat(declarations).extracting(declaration -> declaration.getOrigin().getKind()).containsExactly(
                ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.FACTORY_RAW, ConfigurationOrigin.Kind.SESSION,
                ConfigurationOrigin.Kind.JAVA_RAW, ConfigurationOrigin.Kind.JAVA);
        assertThat(declarations).allSatisfy(declaration ->
                assertThat(declaration.getSetting()).isEqualTo(ConnectionSettings.projectedProperty(property)));
        assertThat(declarations.get(0).getOrigin().description()).contains("simplejavamail.defaults.readtimeoutmillis");
        assertThat(declarations.get(1).getOrigin().description()).contains("simplejavamail.extraproperties." + property);
        assertThat(declarations.get(3).getOrigin().description()).contains("withProperty/withProperties(" + property + ")");
        assertThat(declarations.get(4).getOrigin().description()).contains("withConnectionConfig");
        ConnectionConfigurationResolver.validateDeclarations(resolved);
        assertThat(session.getProperty(property)).isEqualTo("123");
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void inactiveProtocolPropertiesRemainSeparateLiteralInputs(final TransportStrategy strategy) {
        final String active = strategy.propertyNameTimeout();
        final String inactive = strategy == TransportStrategy.SMTP ? "mail.smtps.timeout" : "mail.smtp.timeout";
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        properties.setProperty(active, "123");
        properties.setProperty(inactive, "456");
        final Session session = Session.getInstance(properties);
        final MailerFromSessionBuilderImpl builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session)
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build());

        final ResolvedConnection resolved = ConnectionConfigurationResolver.resolve(builder.captureConnectionInputs(session));
        assertThat(ConnectionSettings.projectedProperty(active)).isNotEqualTo(ConnectionSettings.projectedProperty(inactive));
        assertThat(resolved.declarations).containsKey(ConnectionSettings.projectedProperty(active))
                .doesNotContainKey(ConnectionSettings.projectedProperty(inactive));
        ConnectionConfigurationResolver.validateDeclarations(resolved);
        assertThat(properties.getProperty(inactive)).isEqualTo("456");
    }

    @Test
    void anEmbeddedProxyPortRetainsItsOriginalPropertyWhileTargetingThePortMember() {
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load())
                .withSMTPServerHost("relay.example.org").withProperty("mail.smtp.socks.host", "proxy.example.org:1081")
                .withProxyConfig(SocksProxyConfig.builder().withHost("proxy.example.org").withPort(1081).build());
        final ResolvedConnection resolved = ConnectionConfigurationResolver.resolve(builder.captureConnectionInputs(null));
        final ConfigurationSetting<Object> port = ConnectionSettings.projectedProperty("mail.smtp.socks.port");
        final List<ConfigurationDeclaration<Object>> declarations = resolved.declarations.get(port);

        assertThat(declarations).extracting(declaration -> declaration.getOrigin().getKind())
                .containsExactly(ConfigurationOrigin.Kind.JAVA_RAW, ConfigurationOrigin.Kind.JAVA);
        assertThat(declarations.get(0).getOrigin().description()).contains("mail.smtp.socks.host");
        assertThat(declarations).allSatisfy(declaration -> assertThat(declaration.getSetting()).isEqualTo(port));
        ConnectionConfigurationResolver.validateDeclarations(resolved);
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void retainedGroupsRejectDeclarationsForAnotherLogicalSettingInEitherPolicy(final ConnectionConflictPolicy policy) {
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load())
                .withSMTPServerHost("relay.example.org").withConnectionConflictPolicy(policy);
        final CapturedConnectionInputs inputs = builder.captureConnectionInputs(null);
        final ResolvedConnection valid = ConnectionConfigurationResolver.resolve(inputs);
        final ConfigurationDeclaration<Object> unrelated = ConfigurationDeclaration.of(
                ConfigurationSetting.of("email", "subject"), ConfigurationChoice.set("123"),
                ConfigurationOrigin.of(ConfigurationOrigin.Kind.JAVA, "withSubject", "Java"));

        assertThatThrownBy(() -> new ResolvedConnection(inputs, valid.overrides, valid.proxy, valid.explicitOverrides,
                valid.shadowedFactoryClassProperty, Map.of(ConnectionSettings.projectedProperty("mail.smtp.timeout"), List.of(unrelated))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("logical setting");
    }
}
