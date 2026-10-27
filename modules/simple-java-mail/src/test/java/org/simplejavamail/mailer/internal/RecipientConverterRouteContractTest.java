package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.ADDRESS;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.NAME;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.KEY_BYTES;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.captured;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class RecipientConverterRouteContractTest {
    static Stream<Method> routes() {
        return EmailContractTestSupport.routes(IRecipientBuilder.class, IRecipientsBuilder.class,
                ConfiguredEmailConverter.EmailConversion.class, ConfiguredEmailConverter.EmlConversion.class,
                ConfiguredEmailConverter.IncomingConversion.class, ConfiguredEmailConverter.OutlookConversion.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void eachRoutePreservesItsDeclaredValueAndOwnership(final Method method, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        if (method.getDeclaringClass() == IRecipientBuilder.class) { recipient(method, fixture); }
        else if (method.getDeclaringClass() == IRecipientsBuilder.class) { group(method, fixture); }
        else { converter(method, fixture); }
    }

    private static void recipient(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final X509Certificate oldCertificate = mock(X509Certificate.class);
        final IRecipientBuilder builder = fixture.mail.recipientBuilder().withAddress("old@example.org").withName("old")
                .withType(RecipientType.TO).withSmimeCertificate(oldCertificate).withDeliveryStatusNotificationNotifyOptions(NotifyOption.FAILURE);
        final String name = method.getName();
        Object[] args = new Object[0];
        if (name.equals("withName")) { args = new Object[] {NAME}; }
        else if (name.equals("withType")) { args = new Object[] {RecipientType.CC}; }
        else if (name.equals("withSmimeCertificate")) { args = new Object[] {fixture.certificate}; }
        else if (name.equals("withDeliveryStatusNotificationNotifyOptions")) { args = new Object[] {new NotifyOption[] {NotifyOption.SUCCESS}}; }
        else if (name.startsWith("withAddress")) {
            final Object address = method.getParameterTypes()[0] == String.class ? "Embedded <" + ADDRESS + ">" : new InternetAddress(ADDRESS, "Embedded");
            args = method.getParameterCount() == 2 ? new Object[] {address, NAME} : new Object[] {address};
        }
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final Recipient result = builder.build();
        assertThat(result.getAddress()).isEqualTo(name.startsWith("withAddress") ? ADDRESS : "old@example.org");
        final String expectedName = name.equals("withName") || name.equals("withAddressAndFixedNameOrProvided") ? NAME
                : name.equals("withAddressAndNameOrDefault") ? "Embedded" : "old";
        assertThat(result.getName()).isEqualTo(expectedName);
        assertThat(result.getType()).isEqualTo(name.equals("withType") ? RecipientType.CC : RecipientType.TO);
        assertThat(result.getSmimeCertificate()).isSameAs(name.equals("clearingSmimeCertificate") ? null : name.equals("withSmimeCertificate") ? fixture.certificate : oldCertificate);
        assertThat(result.getDeliveryStatusNotificationNotifyOptions()).isEqualTo(name.equals("clearingDeliveryStatusNotificationNotifyOptions")
                ? Set.of() : name.equals("withDeliveryStatusNotificationNotifyOptions") ? Set.of(NotifyOption.SUCCESS) : Set.of(NotifyOption.FAILURE));
        recordRecipientFields();
    }

    private static void group(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final String name = method.getName();
        if (name.contains("Smime") || name.contains("DeliveryStatusNotification")) { groupPolicy(method, fixture); return; }
        final IRecipientsBuilder builder = fixture.mail.recipientsBuilder().withFixedSmimeCertificate(fixture.certificate)
                .withFixedDeliveryStatusNotificationNotifyOptions(NotifyOption.SUCCESS);
        final List<Object> args = new ArrayList<>();
        final boolean rawCollection = name.contains("Addresses") || name.contains("Name") || method.getParameterCount() > 2;
        for (int i = 0; i < method.getParameterCount(); i++) {
            final Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) { args.add(method.getParameterCount() == 2 ? "Embedded <" + ADDRESS + ">" : i == 0 ? NAME : "Embedded <" + ADDRESS + ">"); }
            else if (type == boolean.class) { args.add(true); }
            else if (type == RecipientType.class) { args.add(RecipientType.CC); }
            else if (type == Recipient.class) { args.add(fixture.recipient); }
            else if (type == Recipient[].class) { args.add(new Recipient[] {fixture.recipient}); }
            else if (type == String[].class) { args.add(new String[] {"Embedded <" + ADDRESS + ">"}); }
            else if (Collection.class.isAssignableFrom(type)) { args.add(name.contains("FromAddresses") ? List.of(new InternetAddress(ADDRESS, "Embedded"))
                    : rawCollection ? List.of("Embedded <" + ADDRESS + ">") : List.of(fixture.recipient)); }
            else { throw new AssertionError("No group argument for " + type); }
        }
        assertThat(invoke(builder, method, args.toArray())).isSameAs(builder);
        final Collection<Recipient> result = builder.buildRecipients();
        assertThat(result).hasSize(1);
        final Recipient selected = result.iterator().next();
        assertThat(selected.getAddress()).isEqualTo(ADDRESS);
        final boolean onlyRawAddress = name.equals("withRecipient") && method.getParameterCount() == 2;
        assertThat(selected.getName()).isEqualTo(name.contains("DefaultName") || onlyRawAddress ? "Embedded" : NAME);
        assertThat(selected.getType()).isEqualTo(java.util.Arrays.asList(method.getParameterTypes()).contains(RecipientType.class) ? RecipientType.CC : RecipientType.TO);
        assertThat(selected.getSmimeCertificate()).isSameAs(fixture.certificate);
        assertThat(selected.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NotifyOption.SUCCESS);
        recordRecipientFields();
    }

    private static void groupPolicy(final Method method, final EmailContractTestSupport fixture) {
        final X509Certificate originalCertificate = mock(X509Certificate.class);
        final Recipient supplied = new Recipient("old", "old@example.org", RecipientType.TO, originalCertificate, List.of(NotifyOption.FAILURE));
        final Recipient missing = new Recipient("missing", "missing@example.org", RecipientType.TO, null);
        final IRecipientsBuilder builder = fixture.mail.recipientsBuilder().withRecipients(supplied, missing);
        final String name = method.getName();
        if (name.startsWith("reset")) {
            if (name.contains("Smime")) { builder.clearingSmimeCertificates(); }
            else { builder.clearingDeliveryStatusNotificationNotifyOptions(); }
        }
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {name.contains("Smime")
                ? fixture.certificate : new NotifyOption[] {NotifyOption.SUCCESS}};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final List<Recipient> result = new ArrayList<>(builder.buildRecipients());
        assertThat(result).hasSize(2);
        if (name.contains("Smime")) {
            assertThat(result.get(0).getSmimeCertificate()).isSameAs(name.startsWith("clearing") ? null
                    : name.contains("Fixed") ? fixture.certificate : originalCertificate);
            assertThat(result.get(1).getSmimeCertificate()).isSameAs(name.startsWith("reset") || name.startsWith("clearing") ? null : fixture.certificate);
            assertThat(result.get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(NotifyOption.FAILURE);
        } else {
            assertThat(result.get(0).getDeliveryStatusNotificationNotifyOptions()).isEqualTo(name.startsWith("clearing") ? Set.of()
                    : name.contains("Fixed") ? Set.of(NotifyOption.SUCCESS) : Set.of(NotifyOption.FAILURE));
            assertThat(result.get(1).getDeliveryStatusNotificationNotifyOptions()).isEqualTo(name.startsWith("reset") || name.startsWith("clearing") ? Set.of() : Set.of(NotifyOption.SUCCESS));
            assertThat(result.get(0).getSmimeCertificate()).isSameAs(originalCertificate);
        }
        assertThat(supplied.getSmimeCertificate()).isSameAs(originalCertificate);
        assertThat(supplied.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NotifyOption.FAILURE);
        record("field", "recipient.certificate"); record("field", "recipient.notify"); record("rule", "recipient.policy");
    }

    private static void recordRecipientFields() {
        for (String field : List.of("name", "address", "type", "certificate", "notify")) { record("field", "recipient." + field); }
    }

    private static void converter(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final String name = method.getName();
        final boolean outlook = method.getDeclaringClass() == ConfiguredEmailConverter.OutlookConversion.class;
        final byte[] bytes = outlook ? EmailContractTestSupport.resourceBytes("/test-messages/simple email with TO and CC.msg") : EmailBootstrapRouteContractTest.EML;
        final EmailContractTestSupport.BorrowedInput input = new EmailContractTestSupport.BorrowedInput(bytes);
        final ConfiguredEmailConverterImpl converter = spy((ConfiguredEmailConverterImpl) fixture.mail.converter());
        if (method.getDeclaringClass() == ConfiguredEmailConverter.IncomingConversion.class) {
            doReturn(fixture.mail.emailBuilder().startingBlank().withSubject("Imported"))
                    .when(converter).mimeMessageToEmailBuilder(any(MimeMessage.class), nullable(Pkcs12Config.class), nullable(OpenPgpReceiveConfig.class), anyBoolean());
        }
        if (outlook && name.contains("Smime")) {
            final OutlookEmailConversionResult imported = mock(OutlookEmailConversionResult.class);
            doReturn(fixture.mail.emailBuilder().startingBlank().withSubject("Imported")).when(imported).getEmailBuilder();
            doReturn(imported).when(converter).outlookMsgToEmailBuilderWithOutlookData(any(java.io.InputStream.class), nullable(Pkcs12Config.class));
        }
        final Object receiver;
        if (method.getDeclaringClass() == ConfiguredEmailConverter.EmailConversion.class) {
            receiver = converter.fromEmail(fixture.mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                    .withSubject("Source").withPlainText("Body").buildEmail());
        } else if (outlook) { receiver = converter.fromOutlookMsg(input); }
        else { receiver = converter.fromEml(input); }
        final Session session = Session.getInstance(new Properties());
        final EmailGovernance governance = new MailerRegularBuilderImpl(org.simplejavamail.config.ConfigLoader.builder().load())
                .withEmailOverrides(fixture.mail.emailBuilder().startingBlank().withSubject("Governed").buildEmail()).buildEmailGovernance();
        final OpenPgpReceiveConfig pgp = OpenPgpReceiveConfig.builder().addVerificationKeyRing(KEY_BYTES).build();
        final String field;
        final Object selected;
        if (name.contains("Session")) { field = "session"; selected = session; }
        else if (name.contains("Governance")) { field = "governance"; selected = governance; }
        else if (name.contains("Smime")) { field = "pkcs12Config"; selected = fixture.store; }
        else if (name.contains("OpenPgp")) { field = "openPgpReceiveConfig"; selected = pgp; }
        else { field = "fetchAttachmentData"; selected = false; }
        if (name.startsWith("clear") || name.startsWith("reset")) {
            final Method seed = java.util.Arrays.stream(method.getDeclaringClass().getMethods()).filter(candidate -> candidate.getName().equals(
                    field.equals("session") ? "withSession" : field.equals("governance") ? "withEmailGovernance" : field.equals("pkcs12Config")
                            ? "withSmimeDecryption" : field.equals("openPgpReceiveConfig") ? "withOpenPgpReceiveConfig" : "withFetchAttachmentData")).findFirst().orElseThrow();
            seed.invoke(receiver, selected);
        }
        assertThat(invoke(receiver, method, method.getParameterCount() == 0 ? new Object[0] : new Object[] {selected})).isSameAs(receiver);
        final Object expected = name.startsWith("clear") || name.startsWith("reset") ? field.equals("fetchAttachmentData")
                ? org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA : null : selected;
        assertThat(captured(receiver, field)).isSameAs(expected);
        if (field.equals("fetchAttachmentData")) { assertThat(captured(receiver, "attachmentDataConfigured")).isEqualTo(!name.startsWith("reset")); }
        assertThat(input.available()).isEqualTo(bytes.length);
        assertThat(input.closed).isFalse();
        if (field.equals("session") || field.equals("governance")) {
            final MimeMessage rendered = receiver instanceof ConfiguredEmailConverter.EmailConversion
                    ? ((ConfiguredEmailConverter.EmailConversion) receiver).toMimeMessage()
                    : receiver instanceof ConfiguredEmailConverter.OutlookConversion ? ((ConfiguredEmailConverter.OutlookConversion) receiver).toMimeMessage()
                    : ((ConfiguredEmailConverter.EmlConversion) receiver).toMimeMessage();
            if (field.equals("session")) {
                if (expected == null) { assertThat(rendered.getSession()).isNotSameAs(session); }
                else { assertThat(rendered.getSession()).isSameAs(session); }
            } else { assertThat(rendered.getSubject()).isEqualTo(expected == null ? outlook ? "Test E-Mail" : "Source" : "Governed"); }
        } else if (outlook) {
            assertThat(((ConfiguredEmailConverter.OutlookConversion) receiver).toEmailBuilder().getSubject()).isEqualTo("Imported");
            verify(converter).outlookMsgToEmailBuilderWithOutlookData(any(java.io.InputStream.class), expected == null ? isNull() : same(fixture.store));
        } else {
            assertThat(((ConfiguredEmailConverter.IncomingConversion<?>) receiver).toEmailBuilder().getSubject()).isEqualTo("Imported");
            verify(converter).mimeMessageToEmailBuilder(any(MimeMessage.class), field.equals("pkcs12Config") && expected != null ? same(fixture.store) : isNull(),
                    field.equals("openPgpReceiveConfig") && expected != null ? same(pgp) : isNull(),
                    eq(field.equals("fetchAttachmentData") ? (Boolean) expected : org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA));
        }
        assertThat(input.closed).isFalse();
        record("field", "converter." + (field.equals("pkcs12Config") ? "smime" : field.equals("openPgpReceiveConfig") ? "openpgp"
                : field.equals("fetchAttachmentData") ? "fetchAttachments" : field));
        record("rule", "converter.options");
    }
}
