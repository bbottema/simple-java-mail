package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.internal.converter.ImportTraversal;
import org.simplejavamail.internal.smime.FactorySmimeRuntime;
import org.simplejavamail.internal.util.InternalEmailConverter;
import org.simplejavamail.mailer.MailerHelper;
import testutil.testrules.SmtpServerExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

@Timeout(60)
class FactorySmimeRoutingTest {
    @RegisterExtension final SmtpServerExtension firstServer = new SmtpServerExtension(0, null, null, false);
    @RegisterExtension final SmtpServerExtension secondServer = new SmtpServerExtension(0, null, null, false);

    @Test
    void pooledSelectionUsesTheActualFactoryAcrossIndependentFactories() throws Exception {
        final SimpleJavaMail first = factory(0);
        final SimpleJavaMail second = factory(1);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final UUID cluster = UUID.randomUUID();
        try (Mailer initiating = builder(first, firstServer, cluster).buildMailer();
                Mailer peer = builder(second, secondServer, cluster).buildMailer()) {
            final Email email = message(first, keys);
            initiating.sync().sendMail(email);
            initiating.sync().sendMail(email);
            assertThat(firstServer.getMessages()).hasSize(1);
            assertThat(secondServer.getMessages()).hasSize(1);
            assertThat(entries(first)).isEmpty();
            assertThat(entries(second)).containsOnlyKeys(keys);
            assertThat(module(first)).isNotSameAs(module(second));
            assertThat(field(org.simplejavamail.internal.moduleloader.ModuleLoader.loadSmimeModule(), "keyCache")).isNull();
        }
    }

    @Test
    void borrowedCrossFactoryFailoverUsesTheSelectedDestinationCache() throws Exception {
        final SimpleJavaMail primary = factory(0);
        final SimpleJavaMail backup = factory(1);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        try (Mailer fallback = backup.mailerBuilder().withSMTPServer("localhost", secondServer.getPort()).buildMailer();
                Mailer mailer = primary.mailerBuilder().withSMTPServer("localhost", unusedPort())
                        .withFailoverMailer(fallback, FailoverPolicy.builder().build()).buildMailer()) {
            mailer.sync().sendMail(message(primary, keys));
            assertThat(secondServer.getMessages()).hasSize(1);
            assertThat(module(primary)).isNull();
            assertThat(entries(backup)).containsOnlyKeys(keys);
        }
    }

    @Test
    void namedPrivateDestinationsShareTheCreatingFactoryPolicy() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), 0,
                ConfigKeys.SMTP_HOST.key(), "localhost", ConfigKeys.SMTP_PORT.key(), unusedPort(),
                ConfigKeys.FAILOVER_MAILER.key(), "backup",
                "simplejavamail.mailers.backup.smtp.host", "localhost",
                "simplejavamail.mailers.backup.smtp.port", secondServer.getPort())).load());
        try (Mailer mailer = mail.mailerBuilder().buildMailer()) {
            mailer.sync().sendMail(message(mail, loadPkcs12KeyStore()));
            assertThat(secondServer.getMessages()).hasSize(1);
            assertThat(module(mail)).isNotNull();
            assertThat(entries(mail)).isEmpty();
            final Object named = field(mailer, "failoverMailer");
            assertThat(field(named, "smimeRuntime")).isSameAs(owner(mail));
        }
    }

    @Test
    void lateRehearsalKeepsItsReceiverAfterTheSendingSessionIsReused() throws Exception {
        final SimpleJavaMail first = factory(0);
        final SimpleJavaMail second = factory(2);
        final Pkcs12Config a = loadPkcs12KeyStore();
        final Pkcs12Config b = independentStore(a);
        final Pkcs12Config c = independentStore(a);
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        final Session session = Session.getInstance(properties);
        final Mailer closed = first.mailerBuilder(session).withTransportModeLoggingOnly(true).buildMailer();
        closed.rehearse(message(first, a));
        closed.close();
        try (Mailer replacement = second.mailerBuilder(session).withTransportModeLoggingOnly(true).buildMailer()) {
            replacement.rehearse(message(first, a));
            replacement.rehearse(message(first, b));
            closed.rehearse(message(first, a));
            replacement.rehearse(message(first, c));
            assertThat(entries(first)).isEmpty();
            assertThat(entries(second)).containsOnlyKeys(b, c).doesNotContainKey(a);
            assertThat(module(first)).isNotSameAs(module(second));
        }
    }

    @Test
    void closingOneMailerDoesNotClearAnotherParticipantsFactoryCache() throws Exception {
        final SimpleJavaMail mail = factory(1);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Mailer first = mail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
        try (Mailer second = mail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            first.rehearse(message(mail, keys));
            final Object held = entries(mail).get(keys);
            first.close();
            second.rehearse(message(mail, keys));
            assertThat(entries(mail).get(keys)).isSameAs(held);
        } finally {
            first.close();
        }
    }

    @Test
    void staticProtectionAndArtifactHelpersUseTheConventionalOwner() throws Exception {
        final SimpleJavaMail origin = factory(0);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Email email = message(origin, keys);
        final MimeMessage exported = EmailConverter.emailToMimeMessage(email);
        assertThat(origin.converter().fromMimeMessage(exported).toEmail().getOriginalSmimeDetails().getVerificationStatus())
                .isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        final MimeMessage raw = new MimeMessage(Session.getInstance(new Properties()));
        raw.setFrom("sender@example.org");
        raw.setText("Helper body", "UTF-8");
        raw.saveChanges();
        MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, email);
        assertThat(module(origin)).isNull();
        assertThat(entries(SimpleJavaMail.fromDefaults())).containsKey(keys);
    }

    @Test
    void serializedEmailAndNestedExportBridgeDoNotCaptureOrReplaceTheFactoryOwner() throws Exception {
        final SimpleJavaMail origin = factory(0);
        final SimpleJavaMail target = factory(1);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Email email = message(origin, keys);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) input.readObject(); }
        final ConfiguredEmailConverterImpl converter = (ConfiguredEmailConverterImpl) target.converter();
        final ImportTraversal traversal = ImportTraversal.start(3, 3);
        final Class<?> bridgeType = Class.forName("org.simplejavamail.converter.internal.InternalEmailConverterImpl");
        final Method bind = bridgeType.getDeclaredMethod("forImport", ImportTraversal.class, ConfiguredEmailConverterImpl.class);
        bind.setAccessible(true);
        final InternalEmailConverter bridge = (InternalEmailConverter) bind.invoke(null, traversal, converter);
        final MimeMessage exported = bridge.emailToMimeMessage(restored);
        assertThat(bridge.importTraversal()).isSameAs(traversal);
        assertThat(target.converter().fromMimeMessage(exported).toEmail().getOriginalSmimeDetails().getVerificationStatus())
                .isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(module(origin)).isNull();
        assertThat(entries(target)).containsOnlyKeys(keys);
    }

    private static SimpleJavaMail factory(final int capacity) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), capacity)).load());
    }
    private static Email message(final SimpleJavaMail mail, final Pkcs12Config keys) {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Routed cache body").signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build()).buildEmail();
    }
    private static MailerRegularBuilder<?> builder(final SimpleJavaMail factory, final SmtpServerExtension server, final UUID cluster) {
        return factory.mailerBuilder().withSMTPServer("localhost", server.getPort())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("localhost").build())
                .withProperty("mail.host", "localhost")
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withClusterKey(cluster).withCoreSize(0).withMaxSize(1)
                        .withLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).build());
    }
    private static FactorySmimeRuntime owner(final SimpleJavaMail factory) {
        return ((ConfiguredEmailConverterImpl) factory.converter()).smimeRuntime();
    }
    private static Object module(final SimpleJavaMail factory) throws Exception { return field(owner(factory), "module"); }
    @SuppressWarnings("unchecked")
    private static Map<Pkcs12Config, ?> entries(final SimpleJavaMail factory) throws Exception {
        return (Map<Pkcs12Config, ?>) field(field(module(factory), "keyCache"), "entries");
    }
    private static Object field(final Object owner, final String name) throws Exception {
        assertThat(owner).isNotNull();
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static int unusedPort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Pkcs12Config independentStore(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        store.store(data, keys.getStorePassword());
        return Pkcs12Config.builder().pkcs12Store(data.toByteArray()).storePassword(keys.getStorePassword())
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
    }
}
