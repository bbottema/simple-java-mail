package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.ADDRESS;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class EmailBootstrapRouteContractTest {
    static final List<Class<?>> EXTRA_SETUP_CONTRACTS = List.of(EmailStartingBuilder.class, ExactEmailBuilder.class, ConfiguredEmailConverter.class);
    static final byte[] EML = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Source\r\nMessage-ID: <source@example.org>\r\n"
            + "Date: Thu, 01 Jan 1970 00:00:01 +0000\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody\r\n")
            .getBytes(StandardCharsets.UTF_8);

    static Stream<Method> extraRoutes() {
        return EXTRA_SETUP_CONTRACTS.stream().flatMap(contract -> Arrays.stream(contract.getDeclaredMethods()))
                .filter(method -> method.getReturnType() == EmailPopulatingBuilder.class || method.getReturnType() == ExactEmailBuilder.class
                        || method.getReturnType().getEnclosingClass() == ConfiguredEmailConverter.class)
                .sorted(java.util.Comparator.comparing(testutil.configcontracts.ConfigurationContractCoverage::signature));
    }

    static Stream<Method> templateRoutes() {
        return EmailContractTestSupport.routes(Set.of(ConfigurationAdoptionCatalogue.Owner.MAILER_TEMPLATE_SELECTION), MailerGenericBuilder.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extraRoutes")
    void everyBootstrapAdapterPreservesItsInputContract(final Method method, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        if (method.getDeclaringClass() == EmailStartingBuilder.class) { starting(method, fixture, directory); }
        else if (method.getDeclaringClass() == ExactEmailBuilder.class) { exact(method, fixture); }
        else { converterInput(method, fixture, directory); }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateRoutes")
    void mailerTemplateSelectionHasAnObservedCompletionResult(final Method method, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder()
                .withMap(java.util.Map.of("simplejavamail.defaults.subject", "configured")).load()).withTransportModeLoggingOnly(true);
        final String name = method.getName();
        final boolean override = name.contains("Overrides");
        final Email old = fixture.mail.emailBuilder().startingBlank().withSubject("old").buildEmail();
        if (override) { builder.withEmailOverrides(old); } else { builder.withEmailDefaults(old); }
        final Email chosen = fixture.mail.emailBuilder().startingBlank().withSubject("chosen").buildEmail();
        assertThat(invoke(builder, method, method.getParameterCount() == 0 ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
        final String expected = name.startsWith("withEmail") ? "chosen" : name.equals("withoutEmailDefaults") ? null : "configured";
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(fixture.mail.emailBuilder().startingBlank().buildEmail()).getSubject())
                    .isEqualTo(expected);
        }
        record("rule", "email.template-selection");
    }

    private static void starting(final Method method, final EmailContractTestSupport fixture, final Path directory) throws Exception {
        final String name = method.getName();
        final EmailPopulatingBuilder sourceBuilder = fixture.mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withSubject("Source").withPlainText("Body").withHTMLText("<p>Body</p>").fixingMessageId("<source@example.org>").fixingSentDate(Instant.ofEpochSecond(1));
        final Email source = sourceBuilder.buildEmail();
        final MimeMessage mime = fixture.mail.converter().fromEmail(source).toMimeMessage();
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(EML);
        final Path path = Files.write(directory.resolve("source.eml"), EML);
        final Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            final Class<?> type = method.getParameterTypes()[i];
            args[i] = type == Email.class ? source : type == EmailPopulatingBuilder.class ? sourceBuilder : type == MimeMessage.class ? mime
                    : type == byte[].class ? EML.clone() : type == InputStream.class ? stream : type == Path.class ? path
                    : type == boolean.class ? true : "<section>%s</section>";
        }
        final Object selected = invoke(fixture.mail.emailBuilder(), method, args);
        if (selected instanceof ExactEmailBuilder) {
            if (method.getParameterTypes()[0] == byte[].class) { Arrays.fill((byte[]) args[0], (byte) 'X'); }
            final Email result = ((ExactEmailBuilder) selected).withEnvelopeRecipients(ADDRESS).buildEmail();
            assertThat(fixture.mail.converter().fromEmail(result).toEmlBytes()).containsExactly(EML);
            assertThat(stream.closed).isFalse();
            record("rule", "email.exact-boundary");
        } else {
            final Email result = ((EmailPopulatingBuilder) selected).buildEmail();
            if (name.equals("startingBlank")) { assertThat(result.getSubject()).isNull(); assertThat(result.getRecipients()).isEmpty(); }
            else if (name.equals("copying")) { assertThat(result.getSubject()).isEqualTo("Source"); assertThat(result.getPlainText()).contains("Body"); }
            else if (name.equals("forwarding")) {
                assertThat(result.getSubject()).contains("Source"); assertThat(result.getEmailToForward()).isNotNull();
                assertThat(result.getEmailToForward().getSubject()).isEqualTo("Source");
                if (method.getParameterTypes()[0] == MimeMessage.class) { assertThat(result.getEmailToForward()).isSameAs(mime); }
                record("field", "email.MAIL_TO_FORWARD");
            } else {
                assertThat(result.getSubject()).contains("Source").isNotEqualTo("Source");
                assertThat(result.getPlainText()).contains("Body");
                assertThat(result.getRecipients()).extracting(Recipient::getAddress).contains("sender@example.org");
                if (Arrays.asList(method.getParameterTypes()).contains(String.class)) {
                    assertThat(result.getHTMLText()).contains("<section>", "Body");
                }
            }
            assertThat(source.getSubject()).isEqualTo("Source");
            record("rule", "email.starting-choices");
        }
    }

    private static void exact(final Method method, final EmailContractTestSupport fixture) {
        final ExactEmailBuilder builder = fixture.mail.emailBuilder().startingFromExactEml(EML).withEnvelopeRecipients("old@example.org")
                .withEnvelopeSender("old@example.org").withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withTlsRequiredForOnwardDelivery().build());
        final String name = method.getName();
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {name.equals("withEnvelopeSender") ? ADDRESS
                : name.equals("withSmtpDeliveryOptions") ? SmtpDeliveryOptions.builder().withSendingToAcceptedRecipients(true).build()
                : method.getParameterTypes()[0] == Recipient[].class ? new Recipient[] {fixture.recipient}
                : method.getParameterTypes()[0] == String[].class ? new String[] {ADDRESS} : List.of(ADDRESS)};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        if (name.contains("EnvelopeRecipients")) {
            assertThat(builder.getEnvelopeRecipients()).extracting(Recipient::getAddress).containsExactlyElementsOf(name.startsWith("clear")
                    ? List.of() : List.of("old@example.org", ADDRESS));
            if (name.startsWith("clear")) { builder.withEnvelopeRecipients(ADDRESS); }
            record("field", "email.envelopeRecipients");
        } else if (name.contains("EnvelopeSender")) {
            assertThat(builder.getEnvelopeSender() == null ? null : builder.getEnvelopeSender().getAddress()).isEqualTo(name.startsWith("clear") ? null : ADDRESS);
            record("field", "email.envelopeSender");
        } else {
            assertThat(builder.getSmtpDeliveryOptions().isTlsRequiredForOnwardDelivery()).isFalse();
            assertThat(builder.getSmtpDeliveryOptions().getSendingToAcceptedRecipients()).isEqualTo(name.startsWith("useConfigured") ? null : true);
        }
        assertThat(fixture.mail.converter().fromEmail(builder.buildEmail()).toEmlBytes()).containsExactly(EML);
        record("rule", "email.exact-boundary");
    }

    private static void converterInput(final Method method, final EmailContractTestSupport fixture, final Path directory) throws Exception {
        final String name = method.getName();
        final boolean outlook = name.equals("fromOutlookMsg");
        final byte[] bytes = outlook ? EmailContractTestSupport.resourceBytes("/test-messages/simple email with TO and CC.msg") : EML.clone();
        final Path path = Files.write(directory.resolve(outlook ? "source.msg" : "source.eml"), bytes);
        final EmailContractTestSupport.BorrowedInput stream = new EmailContractTestSupport.BorrowedInput(bytes);
        final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new java.io.ByteArrayInputStream(EML));
        final Class<?> parameter = method.getParameterTypes()[0];
        final Object source = parameter == Email.class ? fixture.mail.emailBuilder().startingBlank().from("sender@example.org")
                .to("reader@example.org").withSubject("Source").withPlainText("Body").buildEmail()
                : parameter == MimeMessage.class ? mime : parameter == InputStream.class ? stream
                : parameter == Path.class ? path : parameter == java.io.File.class ? path.toFile()
                : parameter == byte[].class ? bytes : outlook ? path.toString() : new String(bytes, StandardCharsets.UTF_8);
        final Object selected = invoke(fixture.mail.converter(), method, source);
        assertThat(stream.available()).isEqualTo(bytes.length);
        if (parameter == byte[].class) { Arrays.fill(bytes, (byte) 'X'); }
        final String subject;
        if (selected instanceof ConfiguredEmailConverter.EmailConversion) { subject = ((ConfiguredEmailConverter.EmailConversion) selected).toMimeMessage().getSubject(); }
        else if (selected instanceof ConfiguredEmailConverter.OutlookConversion) { subject = ((ConfiguredEmailConverter.OutlookConversion) selected).toEmail().getSubject(); }
        else { subject = ((ConfiguredEmailConverter.IncomingConversion<?>) selected).toEmail().getSubject(); }
        assertThat(subject).isEqualTo(outlook ? "Test E-Mail" : "Source");
        assertThat(stream.closed).isFalse();
        if (parameter == Path.class || parameter == java.io.File.class || outlook && parameter == String.class) { Files.delete(path); }
        record("rule", "converter.input-ownership");
    }
}
