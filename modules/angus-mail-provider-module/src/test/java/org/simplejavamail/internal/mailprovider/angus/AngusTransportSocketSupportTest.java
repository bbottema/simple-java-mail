package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Session;
import jakarta.mail.Provider;
import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AngusTransportSocketSupportTest {
    @Test
    void loadedAngusHasTheRequiredTransportSocketHook() {
        assertThat(AngusTransportSocketSupport.isSupported()).as("transport hook required by authenticated SOCKS").isTrue();
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(Session.getInstance(new Properties())))
                .as("the ordinary provider discovery reaches the same capability").isTrue();
    }

    @Test
    void customProtocolAndProviderAliasesStayOutsideDirectIntegration() throws Exception {
        for (Provider provider : new Provider[]{
                new Provider(Provider.Type.TRANSPORT, "smtp", "org.eclipse.angus.mail.smtp.SMTPSSLTransport", "application", null),
                new Provider(Provider.Type.TRANSPORT, "smtps", "org.eclipse.angus.mail.smtp.SMTPTransport", "application", null),
                new Provider(Provider.Type.TRANSPORT, "custom-smtp", "org.eclipse.angus.mail.smtp.SMTPTransport", "application", null)}) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", provider.getProtocol());
            final Session session = Session.getInstance(properties);
            session.addProvider(provider);
            session.setProvider(provider);
            assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session)).isFalse();
            assertThat(session.getProvider(provider.getProtocol())).isSameAs(provider);
        }
    }

    @Test
    void finalCustomProviderDeclarationsAreObservedWithoutChangingTheCallerSession() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final Provider original = session.getProvider("smtp");
        final Provider custom = new Provider(Provider.Type.TRANSPORT, "smtp", "application.OtherTransport", "application", null);
        session.addProvider(custom);
        final Properties effective = (Properties) session.getProperties().clone();
        effective.setProperty("mail.smtp.class", custom.getClassName());
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session, effective)).isFalse();
        assertThat(session.getProvider("smtp")).isSameAs(original);
        assertThat(session.getProperties()).doesNotContainKey("mail.smtp.class");
        effective.setProperty("mail.smtp.class", "application.UnknownTransport");
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session, effective)).isFalse();
        effective.remove("mail.smtp.class");
        effective.setProperty("mail.transport.protocol", "smtps");
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session, effective)).isTrue();
        assertThat(session.getProperties()).doesNotContainKey("mail.transport.protocol");
    }
}
