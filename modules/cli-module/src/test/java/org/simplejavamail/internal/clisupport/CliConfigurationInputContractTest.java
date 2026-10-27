package org.simplejavamail.internal.clisupport;

import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.internal.clisupport.CliExactEmailConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import picocli.CommandLine;
import testutil.configcontracts.ConfigurationContractCoverage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Frozen option obligations are fulfilled only by actual parsing, conversion, binding and the selected feature's readback. */
class CliConfigurationInputContractTest {
    private static final List<CliDeclaredOptionSpec> OPTIONS = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
    private static final Map<String, CliDeclaredOptionSpec> OPTIONS_BY_ID = OPTIONS.stream().collect(Collectors.toMap(
            CliConfigurationInputContractTest::routeId, Function.identity(), (first, duplicate) -> { throw new AssertionError("Duplicate CLI route"); }, LinkedHashMap::new));
    static final byte[] EML = ("From: source@example.test\r\nTo: source-to@example.test\r\nCc: source-cc@example.test\r\n"
            + "Subject: CLI source\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nCLI source body\r\n").getBytes(StandardCharsets.US_ASCII);
    static final String FILE_BODY = "CLI file body";
    static final UUID SELECTED_UUID = UUID.fromString("aa960e7f-3122-4fe4-8df8-d742fdd6a86d");
    @TempDir Path directory;

    public static Set<String> requiredRoutes() {
        final Set<String> result = new LinkedHashSet<>();
        final InputStream resource = CliConfigurationInputContractTest.class.getResourceAsStream("/cli-configuration-input-routes.tsv");
        if (resource == null) { throw new AssertionError("Missing frozen CLI input route fixture"); }
        try (BufferedReader input = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) { continue; }
                final String[] fields = line.split("\t", -1);
                if (fields.length != 2 || !result.add(fields[0] + "|" + fields[1])) { throw new AssertionError("Invalid frozen CLI route"); }
            }
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        return Set.copyOf(result);
    }

    static Stream<String> routes() { return requiredRoutes().stream().sorted(); }

    @Test
    void generatedBuilderOptionsMatchTheIndependentlyFrozenInputRoutes() {
        assertThat(OPTIONS_BY_ID.keySet()).containsExactlyInAnyOrderElementsOf(requiredRoutes());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void eachGeneratedOptionParsesConvertsAndBindsItsActualBuilderChoice(final String id) throws Exception {
        final CliDeclaredOptionSpec option = OPTIONS_BY_ID.get(id);
        assertThat(option).as(id).isNotNull();
        final FilesFixture files = files();
        final Arguments arguments = sample(option, files);
        final List<String> tokens = new ArrayList<>(List.of("send"));
        final boolean mailer = option.getName().startsWith("--mailer:");
        final boolean starting = option.getSourceMethod().getDeclaringClass().getSimpleName().equals("EmailStartingBuilder");
        final boolean exact = ExactEmailBuilder.class.isAssignableFrom(option.getSourceMethod().getDeclaringClass())
                || CliExactEmailConfigurationBuilder.class.isAssignableFrom(option.getSourceMethod().getDeclaringClass());
        if (exact) { tokens.addAll(List.of("--email:startingFromExactEml", files.eml.toString(), "--email:withEnvelopeRecipients", "before@example.test")); }
        else if (!starting) { tokens.add("--email:startingBlank"); }
        tokens.add(option.getName()); tokens.addAll(arguments.text);
        final CommandLine parser = CliCommandLineProducer.configurePicoCli(OPTIONS, 120);
        final CliReceivedCommand received = CliCommandLineConsumer.consumeCommandLineInput(parser.parseArgs(tokens.toArray(String[]::new)), OPTIONS);
        final CliReceivedOptionData selected = received.getReceivedOptions().get(received.getReceivedOptions().size() - 1);
        assertThat(routeId(selected.getDeclaredOptionSpec())).isEqualTo(id);
        assertConverted(selected.getProvidedOptionValues(), arguments.expected);

        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.defaults.subject", "configured subject", "simplejavamail.mailers.backup.smtp.host", "backup.example.test",
                "simplejavamail.smtp.retry.attempts", 3, "simplejavamail.smtp.retry.delay", "PT0.1S")).load());
        final CliBuilderApiType kind = mailer ? CliBuilderApiType.MAILER : CliBuilderApiType.EMAIL;
        Object current = mailer ? CliMailerInputFixture.startingBuilder(mail, option.getName()) : mail.emailBuilder();
        if (!mailer) {
            current = bind(received.getReceivedOptions().subList(0, received.getReceivedOptions().size() - 1), kind, current);
            CliEmailInputFixture.seed(option.getName(), current, files);
        }
        final Object bound = bind(List.of(selected), kind, current);
        assertThat(bound).as("Actual builder after %s", option.getName()).isNotNull();
        if (mailer) { CliMailerInputFixture.assertBound(option.getName(), bound, selected.getProvidedOptionValues()); }
        else { CliEmailInputFixture.assertBound(option.getName(), bound, selected.getProvidedOptionValues(), files); }
        record("cli", id);
    }

    enum MessageRepresentation { MSG, EML_EXTENSIONLESS, EML_MSG_SUFFIX, MSG_EXTENSIONLESS, MSG_EML_SUFFIX }

    @ParameterizedTest
    @EnumSource(MessageRepresentation.class)
    void messageContentSelectsTheRealConverterBeforeTheCopyingAdapter(final MessageRepresentation representation) throws Exception {
        final Path msg = mainTestResource("test-messages/simple email with TO and CC.msg");
        final Path input;
        final String expectedSubject;
        switch (representation) {
            case MSG: input = msg.toAbsolutePath(); expectedSubject = "Test E-Mail"; break;
            case EML_EXTENSIONLESS: input = Files.write(directory.resolve("plain-input"), EML); expectedSubject = "CLI source"; break;
            case EML_MSG_SUFFIX: input = Files.write(directory.resolve("plain.msg"), EML); expectedSubject = "CLI source"; break;
            case MSG_EXTENSIONLESS: input = Files.copy(msg, directory.resolve("compound-input")); expectedSubject = "Test E-Mail"; break;
            case MSG_EML_SUFFIX: input = Files.copy(msg, directory.resolve("compound.eml")); expectedSubject = "Test E-Mail"; break;
            default: throw new AssertionError(representation);
        }
        final CliDeclaredOptionSpec copying = OPTIONS.stream().filter(option -> option.getName().equals("--email:copying")).findFirst().orElseThrow();
        final CliReceivedCommand command = CliCommandLineConsumer.consumeCommandLineInput(
                CliCommandLineProducer.configurePicoCli(OPTIONS, 120).parseArgs("send", copying.getName(), input.toString()), OPTIONS);
        final MimeMessage parsed = (MimeMessage) command.getReceivedOptions().get(0).getProvidedOptionValues().get(0);
        assertThat(parsed.getSubject()).isEqualTo(expectedSubject);
        final Object bound = bind(command.getReceivedOptions(), CliBuilderApiType.EMAIL,
                SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder());
        assertThat(((org.simplejavamail.api.email.EmailPopulatingBuilder) bound).getSubject()).isEqualTo(expectedSubject);
        record("cli", routeId(copying));
    }

    private static Object bind(final List<CliReceivedOptionData> options, final CliBuilderApiType kind, final Object builder) throws Exception {
        final Method apply = CliCommandLineConsumerResultHandler.class.getDeclaredMethod("applyBuilderOptions", List.class, CliBuilderApiType.class, Object.class);
        apply.setAccessible(true);
        try { return apply.invoke(null, options, kind, builder); }
        catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception) { throw (Exception) cause; }
            throw (Error) cause;
        }
    }

    private FilesFixture files() throws Exception {
        final Path text = Files.writeString(directory.resolve("input.txt"), FILE_BODY, StandardCharsets.UTF_8);
        final Path eml = Files.write(directory.resolve("input.eml"), EML);
        final Path store = Files.write(directory.resolve("store.p12"), new byte[] {1, 2, 3});
        final Path certificate = Files.copy(mainTestResource("pkcs12/smime_test_user.pem.standard.crt"), directory.resolve("recipient.crt"));
        return new FilesFixture(text, eml, store, certificate, directory);
    }

    /** Reactor tests run from the CLI module; isolated contract probes also run from the repository root. */
    private static Path mainTestResource(final String relative) {
        for (Path root : List.of(Path.of("modules/simple-java-mail/src/test/resources"), Path.of("../simple-java-mail/src/test/resources"))) {
            final Path resource = root.resolve(relative).toAbsolutePath().normalize();
            if (Files.isRegularFile(resource)) { return resource; }
        }
        throw new AssertionError("Missing main-module CLI input fixture: " + relative);
    }

    private static Arguments sample(final CliDeclaredOptionSpec option, final FilesFixture files) throws Exception {
        final List<String> text = new ArrayList<>();
        final List<Object> expected = new ArrayList<>();
        final Class<?>[] types = option.getSourceMethod().getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            final Class<?> type = types[index];
            final String name = option.getPossibleOptionValues().get(index).getName();
            final String method = option.getSourceMethod().getName();
            String token;
            Object value;
            if (type == String.class || type == Object.class) { token = textValue(option, name, files); value = token; }
            else if (type == boolean.class || type == Boolean.class) { token = "true"; value = true; }
            else if (type == int.class || type == Integer.class) {
                final int number = name.toLowerCase(java.util.Locale.ROOT).contains("port") ? name.equals("proxyPort") ? 1081 : 2525
                        : name.toLowerCase(java.util.Locale.ROOT).contains("time") || name.equals("timeout") || name.equals("keepAliveTime") ? 700
                        : name.equals("multiplier") ? 2 : 3;
                token = Integer.toString(number); value = number;
            } else if (type == long.class || type == Long.class) { final long number = method.contains("Memory") ? 256L * 1024 * 1024 : 64L * 1024 * 1024; token = Long.toString(number); value = number; }
            else if (type == double.class || type == Double.class) { token = "0.25"; value = 0.25d; }
            else if (type == Duration.class) { token = "PT30S"; value = Duration.ofSeconds(30); }
            else if (type == UUID.class) { token = SELECTED_UUID.toString(); value = SELECTED_UUID; }
            else if (type == Date.class) { token = "2026-10-26"; value = new SimpleDateFormat("yyyy-MM-dd").parse(token); }
            else if (type == File.class) { final Path path = name.toLowerCase(java.util.Locale.ROOT).contains("pem") ? files.certificate
                        : name.toLowerCase(java.util.Locale.ROOT).contains("store") ? files.store : files.text; token = path.toString(); value = path.toFile(); }
            else if (type == MimeMessage.class) { token = files.eml.toString(); value = "mime"; }
            else if (type == DataSource.class) { token = files.text.toString(); value = "data"; }
            else if (type == byte[].class) { token = files.eml.toString(); value = EML; }
            else if (type == String[].class) {
                token = method.equals("trustingSSLHosts") ? "relay.example.test" : "alpha@example.test,beta@example.test";
                value = CliEmailRecipientBuilder.class.isAssignableFrom(option.getSourceMethod().getDeclaringClass())
                        ? new String[] {token} : method.equals("trustingSSLHosts") ? new String[] {token} : new String[] {"alpha@example.test", "beta@example.test"};
            } else if (type == DeliveryStatusNotification.NotifyOption[].class) { token = "failure,delay"; value = new DeliveryStatusNotification.NotifyOption[] {DeliveryStatusNotification.NotifyOption.FAILURE, DeliveryStatusNotification.NotifyOption.DELAY}; }
            else if (type == Message.RecipientType.class) { token = "TO"; value = Message.RecipientType.TO; }
            else if (type.isEnum()) { value = enumValue(type); token = ((Enum<?>) value).name(); }
            else { throw new AssertionError("Missing independent CLI sample for " + type.getName() + " in " + option.getName()); }
            text.add(token); expected.add(value);
        }
        return new Arguments(text, expected);
    }

    private static String textValue(final CliDeclaredOptionSpec option, final String name, final FilesFixture files) {
        final String method = option.getSourceMethod().getName();
        if (name.equals("propertyName")) { return "application.cli"; }
        if (name.equals("propertyValue") || name.equals("value")) { return "cli-value"; }
        if (name.equals("name") || name.equals("fixedName")) {
            if (method.equals("withFailoverMailerName")) { return "backup"; }
            if (method.equals("withHeader")) { return "X-CLI"; }
            return "CLI Person";
        }
        if (name.equals("localBindAddress")) { return "127.0.0.1"; }
        if (name.startsWith("oneOrMore")) { return "alpha@example.test,beta@example.test"; }
        if (name.toLowerCase(java.util.Locale.ROOT).contains("address") || name.equals("recipientAddresses")) { return "alpha@example.test"; }
        if (name.equals("host")) { return "relay.example.test"; }
        if (name.equals("proxyHost")) { return "proxy.example.test"; }
        if (name.equals("smtpClientHostname")) { return "client.example.test"; }
        if (name.equals("username") || name.equals("proxyUsername")) { return "cli-user"; }
        if (name.toLowerCase(java.util.Locale.ROOT).contains("password")) { return "cli-password"; }
        if (name.equals("group")) { return "cli-group"; }
        if (name.equals("factoryClass")) { return "javax.net.ssl.SSLSocketFactory"; }
        if (name.equals("embeddedImageBaseDir")) { return files.directory.toString(); }
        if (name.equals("embeddedImageBaseClassPath")) { return "cli/assets"; }
        if (name.equals("embeddedImageBaseUrl")) { return "https://example.test/cli/"; }
        if (name.equals("keyEncapsulationAlgorithm")) { return "RSA"; }
        if (name.equals("cipherAlgorithm")) { return "AES256_CBC"; }
        if (name.equals("signatureAlgorithm")) { return "SHA256withRSA"; }
        if (name.equals("keyAlias")) { return "cli-key"; }
        if (name.toLowerCase(java.util.Locale.ROOT).contains("template")) { return "CLI quoted: %s"; }
        if (method.equals("withRecipients") && name.equals("name")) { return "CLI Person"; }
        return "CLI value";
    }

    private static Object enumValue(final Class<?> type) {
        if (type == TransportStrategy.class) { return TransportStrategy.SMTP_TLS; }
        if (type == ConnectionConflictPolicy.class) { return ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES; }
        if (type == LoadBalancingStrategy.class) { return LoadBalancingStrategy.RANDOM_ACCESS; }
        if (type == SessionDebugOutput.class) { return SessionDebugOutput.STDERR; }
        if (type == AsyncQueueOverflowPolicy.class) { return AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY; }
        if (type == ContentTransferEncoding.class) { return ContentTransferEncoding.BASE_64; }
        if (type == CalendarMethod.class) { return CalendarMethod.REQUEST; }
        if (type == DeliveryStatusNotification.ReturnOption.class) { return DeliveryStatusNotification.ReturnOption.HEADERS_ONLY; }
        throw new AssertionError("Missing independent CLI enum sample for " + type.getName());
    }

    private static void assertConverted(final List<Object> actual, final List<Object> expected) throws Exception {
        assertThat(actual).hasSize(expected.size());
        for (int index = 0; index < actual.size(); index++) {
            final Object value = actual.get(index);
            final Object wanted = expected.get(index);
            if (value instanceof MimeMessage) { assertThat(((MimeMessage) value).getSubject()).isEqualTo("CLI source"); }
            else if (value instanceof DataSource) { try (InputStream input = ((DataSource) value).getInputStream()) { assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(FILE_BODY); } }
            else if (wanted instanceof byte[]) { assertThat((byte[]) value).containsExactly((byte[]) wanted); }
            else if (wanted instanceof Object[]) { assertThat((Object[]) value).containsExactly((Object[]) wanted); }
            else { assertThat(value).as("Converted parameter %s", index).isEqualTo(wanted); }
        }
    }

    static String routeId(final CliDeclaredOptionSpec option) { return option.getName() + "|" + ConfigurationContractCoverage.signature(option.getSourceMethod()); }

    static final class FilesFixture {
        final Path text;
        final Path eml;
        final Path store;
        final Path certificate;
        final Path directory;
        FilesFixture(final Path text, final Path eml, final Path store, final Path certificate, final Path directory) {
            this.text = text; this.eml = eml; this.store = store; this.certificate = certificate; this.directory = directory;
        }
    }

    private static final class Arguments {
        final List<String> text;
        final List<Object> expected;
        Arguments(final List<String> text, final List<Object> expected) { this.text = text; this.expected = expected; }
    }
}
