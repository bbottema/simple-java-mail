package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.RecipientDeliveryReport;
import org.simplejavamail.api.email.EnhancedStatusCode;
import org.simplejavamail.api.email.ReportedRecipient;
import org.simplejavamail.api.email.ReportedMailServer;
import org.simplejavamail.api.email.DeliveryDiagnostic;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendSubmissionStep;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.config.SimpleJavaMailConfig;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiBoundaryTest {
    @TempDir Path output;
    private String compilationDetails;

    @Test
    void mailerTryWithResourcesNeedsOnlyItsTwoShutdownExceptions() throws Exception {
        final Path source = output.resolve("MailerLifetime.java");
        Files.writeString(source, "import org.simplejavamail.api.mailer.Mailer;\n"
                + "import java.util.concurrent.ExecutionException;\n"
                + "class MailerLifetime {\n"
                + " void finish(Mailer mailer) throws ExecutionException, InterruptedException {\n"
                + "  try (Mailer owned = mailer) { owned.getTransportStrategy(); }\n"
                + " }\n}\n");
        assertThat(compile(source)).as(compilationDetails).isTrue();
    }

    @Test
    void telemetryConfigurationDoesNotExposeRecordingOrFrameworkTypes() {
        assertThat(MailSendTelemetry.class.getMethods()).isEmpty();
        assertThat(MailerGenericBuilder.class.getMethods())
                .allSatisfy(method -> assertThat(method.toGenericString()).doesNotContain("io.opentelemetry"));
    }

    private static final List<Class<?>> READ_CONTRACTS = List.of(MailSendDiagnostics.class, MailSendMeasurement.class, MailSendOutcome.class,
            MailSendProgress.class, MailSendConnectionStep.class, MailSendSubmissionStep.class, MailSubmissionReceipt.class, MailRehearsal.class,
            AsyncQueueSnapshot.class, OutlookMessageData.class, OutlookEmailConversionResult.class, SimpleJavaMailConfig.class,
            DeliveryStatusReport.class, RecipientDeliveryReport.class, EnhancedStatusCode.class, ReportedRecipient.class,
            ReportedMailServer.class, DeliveryDiagnostic.class);

    @Test
    void libraryProducedResultsHaveNoApplicationConstructionSurface() {
        READ_CONTRACTS.forEach(type -> {
            assertThat(type.isInterface()).as(type.getName()).isTrue();
            assertThat(type.getConstructors()).isEmpty();
            assertThat(type.getDeclaredClasses()).isEmpty();
            assertThat(type.getMethods()).noneMatch(method -> Modifier.isStatic(method.getModifiers()));
            assertThat(type.getMethods()).noneMatch(method -> method.toGenericString().contains(".internal."));
        });
        assertThat(SimpleJavaMailConfig.class.getMethods()).noneMatch(method -> method.getName().equals("getLocks"));
        assertThat(EmailProperty.class.getMethods()).noneMatch(method -> method.getName().equals("getGetter"));
        assertThat(MailSender.class.getMethods()).extracting(java.lang.reflect.Method::getName).containsExactly("sendMail");
    }

    @Test
    void conversionEntryPointsExposeSourceContractsRatherThanResultAssembly() {
        final List<Class<?>> sourceContracts = List.of(ConfiguredEmailConverter.IncomingConversion.class,
                ConfiguredEmailConverter.EmlConversion.class, ConfiguredEmailConverter.MimeMessageConversion.class,
                ConfiguredEmailConverter.EmailConversion.class, ConfiguredEmailConverter.OutlookConversion.class);
        assertThat(ConfiguredEmailConverter.class.isInterface()).isTrue();
        assertThat(ConfiguredEmailConverter.class.getConstructors()).isEmpty();
        assertThat(ConfiguredEmailConverter.class.getDeclaredClasses()).containsExactlyInAnyOrderElementsOf(sourceContracts);
        assertThat(ConfiguredEmailConverter.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .containsOnly("fromEmail", "fromMimeMessage", "fromEml", "fromOutlookMsg");
        Stream.concat(Stream.of(ConfiguredEmailConverter.class), sourceContracts.stream()).forEach(type -> {
            assertThat(type.isInterface()).as(type.getName()).isTrue();
            assertThat(Modifier.isPublic(type.getModifiers())).as(type.getName()).isTrue();
            assertThat(type.getConstructors()).isEmpty();
            assertThat(type.getMethods()).noneMatch(method -> Modifier.isStatic(method.getModifiers()));
            assertThat(type.getMethods()).noneMatch(method -> method.toGenericString().contains(".internal."));
        });
        sourceContracts.forEach(type -> assertThat(type.getDeclaredClasses()).isEmpty());
    }

    @Test
    void flatConfiguredConversionEntryPointsDoNotCompile() throws Exception {
        for (final String invocation : List.of(
                "mimeMessageToEmailBuilder((jakarta.mail.internet.MimeMessage) null)",
                "emlToEmailBuilder((java.io.InputStream) null)",
                "emlToEmailBuilder((java.nio.file.Path) null)",
                "outlookMsgToEmailBuilderWithOutlookData(\"source.msg\", null)",
                "outlookMsgToEmailBuilderWithOutlookData((java.io.File) null, null)",
                "outlookMsgToEmailBuilderWithOutlookData((java.nio.file.Path) null, null)",
                "outlookMsgToEmailBuilderWithOutlookData((java.io.InputStream) null, null)")) {
            final Path source = output.resolve("FlatConversion.java");
            Files.writeString(source, "class FlatConversion { Object result = ((org.simplejavamail.api.converter.ConfiguredEmailConverter) null)."
                    + invocation + "; }");
            assertThat(compile(source)).as(invocation).withFailMessage(compilationDetails).isFalse();
        }
    }

    @Test
    void scopedSendingAndLifecycleUseTheUnifiedPublicContracts() throws Exception {
        assertThat(MailSender.class.getMethod("sendMail", org.simplejavamail.api.email.Email.class).getReturnType())
                .isEqualTo(MailSubmissionReceipt.class);
        assertThat(org.simplejavamail.api.mailer.Mailer.class.getMethod("closeAsync").getReturnType())
                .isEqualTo(java.util.concurrent.CompletableFuture.class);
        assertThat(org.simplejavamail.api.mailer.Mailer.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("shutdownConnectionPool");
    }

    @Test
    void continuingApplicationCodeCompilesWithoutMainImplementation() throws Exception {
        final boolean compiled = compile(Path.of("src/test/core-only/org/simplejavamail/coreconsumer/CoreOnlyConsumer.java"));
        assertThat(compiled).withFailMessage(compilationDetails).isTrue();
    }

    @Test
    void emailConstructionRequiresThePublicBuilderRoute() throws Exception {
        final Path source = output.resolve("DirectEmailConstruction.java");
        Files.writeString(source, "class DirectEmailConstruction { org.simplejavamail.api.email.Email create("
                + "org.simplejavamail.api.email.EmailPopulatingBuilder builder) { return new org.simplejavamail.api.email.Email(builder); } }");
        assertThat(compile(source)).withFailMessage("Direct Email construction must be internal: %s", compilationDetails).isFalse();

        Files.writeString(source, "class DirectEmailConstruction { org.simplejavamail.api.email.Email create("
                + "org.simplejavamail.api.email.EmailPopulatingBuilder builder) { return builder.buildEmail(); } }");
        assertThat(compile(source)).withFailMessage(compilationDetails).isTrue();
    }

    @Test
    void emailConstructorIsProtectedForTheLibraryImplementation() throws Exception {
        assertThat(Email.class.getConstructors()).isEmpty();
        assertThat(Modifier.isProtected(Email.class.getDeclaredConstructor(EmailPopulatingBuilder.class).getModifiers())).isTrue();
    }

    @Test
    void defaultsAndOverridesBookkeepingIsNotAnApplicationContract() {
        assertThat(Path.of("src/main/java/org/simplejavamail/api/email/EmailWithDefaultsAndOverridesApplied.java")).doesNotExist();
    }

    @Test
    void resultAssemblyAndRemovedImplementationEntryPointsDoNotCompile() throws Exception {
        for (final String expression : List.of(
                "org.simplejavamail.api.mailer.MailSendDiagnostics.builder()",
                "org.simplejavamail.api.mailer.MailSendProgress.builder()",
                "org.simplejavamail.api.email.DeliveryStatusReport.builder()",
                "org.simplejavamail.api.email.RecipientDeliveryReport.builder()",
                "org.simplejavamail.api.email.EnhancedStatusCode.builder()",
                "org.simplejavamail.api.email.ReportedRecipient.builder()",
                "org.simplejavamail.api.email.ReportedMailServer.builder()",
                "org.simplejavamail.api.email.DeliveryDiagnostic.builder()",
                "org.simplejavamail.api.email.OpenPgpDetails.builder()",
                "org.simplejavamail.api.email.OpenPgpSignatureResult.builder()",
                "new org.simplejavamail.api.mailer.MailSendMeasurement(null, null, false)",
                "new org.simplejavamail.api.mailer.MailSubmissionReceipt(null, null, null)",
                "new org.simplejavamail.api.outlook.OutlookMessageData()",
                "new org.simplejavamail.recipient.RecipientBuilder()",
                "new org.simplejavamail.converter.ConfiguredEmailConverter(null)",
                "((org.simplejavamail.api.mailer.MailSender) null).sendMailAndGetReceipt(null)",
                "((org.simplejavamail.api.mailer.MailSubmissionReceipt) null).isAcceptedByServer()",
                "((org.simplejavamail.api.mailer.Mailer) null).shutdownConnectionPool()",
                "((org.simplejavamail.api.mailer.MailSendTelemetry) null).bind(false)",
                "io.opentelemetry.api.OpenTelemetry.noop()",
                "org.simplejavamail.otel.OtelAdapter.wrap(null)")) {
            final Path source = output.resolve("Forbidden.java");
            Files.writeString(source, "class Forbidden { Object result = " + expression + "; }");
            assertThat(compile(source)).as(expression).isFalse();
        }
    }

    @Test
    void publicCoreSignaturesDoNotExposeInternalMachinery() throws Exception {
        final Path classes = Path.of("target/classes");
        try (Stream<Path> entries = Files.walk(classes.resolve("org/simplejavamail/api"))) {
            for (final Path file : entries.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                final String name = classes.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                if (!name.contains(".internal.")) {
                    final Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                    if (Modifier.isPublic(type.getModifiers())) {
                        assertThat(type.getMethods()).as(name).noneMatch(method -> method.toGenericString().contains(".internal."));
                        assertThat(type.getConstructors()).as(name).noneMatch(constructor -> constructor.toGenericString().contains(".internal."));
                    }
                }
            }
        }
    }

    @Test
    void everyPublicSelectorHasAnInternalAccessorAndSerializationName() {
        for (final EmailProperty property : EmailProperty.values()) {
            assertThat(org.simplejavamail.internal.config.EmailPropertyAccess.forProperty(property)).isNotNull();
            assertThat(org.simplejavamail.internal.config.EmailProperty.valueOf(property.name())).isNotNull();
        }
    }

    @Test
    void applicationValueBuildersHaveExplicitDocumentedCompanionMethods() throws Exception {
        for (final String relativePath : List.of("api/mailer/config/ConnectionPoolClusterConfig.java", "api/mailer/config/FailoverPolicy.java",
                "api/mailer/config/MailSendRetryPolicy.java", "api/email/config/DkimConfig.java", "api/email/config/SmimeSigningConfig.java",
                "api/email/config/SmimeEncryptionConfig.java", "api/email/config/DeliveryStatusNotification.java", "api/mailer/config/Pkcs12Config.java",
                "api/email/config/OpenPgpSigningConfig.java", "api/email/config/OpenPgpEncryptionConfig.java", "api/email/config/OpenPgpReceiveConfig.java",
                "api/mailer/config/ConnectionPoolConfig.java", "api/mailer/config/AsyncExecutionConfig.java", "api/mailer/config/SocksProxyConfig.java",
                "api/mailer/config/SendingLimitsConfig.java", "api/mailer/config/ConnectionConfig.java", "api/mailer/config/TlsConfig.java",
                "api/mailer/config/ValidationConfig.java", "api/email/config/ImageResolutionConfig.java", "api/email/config/ContentEncodingConfig.java",
                "api/email/config/EmailTemplateOptions.java", "api/email/config/SmtpDeliveryOptions.java")) {
            final String source = Files.readString(Path.of("src/main/java/org/simplejavamail", relativePath));
            assertThat(source).as(relativePath).doesNotContain("import lombok.Builder", "@Builder");
            final Matcher builderDeclaration = Pattern.compile("public static (?:final )?class \\w*Builder\\b").matcher(source);
            assertThat(builderDeclaration.find()).as(relativePath).isTrue();
            final Matcher methods = Pattern.compile(
                    "(?s)/\\*\\*((?:(?!\\*/).)*)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*public [^{};]+? (get\\w+|is\\w+|clear\\w+|reset\\w+)\\(")
                    .matcher(source.substring(builderDeclaration.start()));
            final List<String> documentedMethods = new ArrayList<>();
            while (methods.find()) {
                assertThat(methods.group(1)).as(relativePath + "#" + methods.group(2)).contains("@see #");
                documentedMethods.add(methods.group(2));
            }
            final Class<?> valueType = Class.forName("org.simplejavamail." + relativePath.replace('/', '.').replace(".java", ""));
            final Class<?> builderType = Arrays.stream(valueType.getDeclaredClasses())
                    .filter(type -> Modifier.isPublic(type.getModifiers()) && type.getSimpleName().endsWith("Builder"))
                    .findFirst().orElseThrow();
            final List<String> companionMethods = Arrays.stream(builderType.getDeclaredMethods())
                    .map(method -> method.getName()).filter(name -> name.matches("(get|is|clear|reset)\\w+"))
                    .collect(Collectors.toList());
            assertThat(documentedMethods).as(relativePath).containsExactlyInAnyOrderElementsOf(companionMethods).isNotEmpty();
        }
    }

    @Test
    void configurationCompanionChecksCoverEachReviewedChoice() {
        final Map<Class<?>, List<String>> companions = Map.of(
                org.simplejavamail.api.email.config.DeliveryStatusNotification.DeliveryStatusNotificationBuilder.class,
                List.of("getReturnOption", "clearReturnOption", "getNotifyOptions", "clearNotifyOptions", "getEnvelopeId", "clearEnvelopeId"),
                org.simplejavamail.api.mailer.config.Pkcs12Config.Pkcs12ConfigBuilder.class,
                List.of("getPkcs12StoreData", "getStorePassword", "getKeyAlias", "getKeyPassword"),
                org.simplejavamail.api.email.config.OpenPgpSigningConfig.OpenPgpSigningConfigBuilder.class,
                List.of("getSecretKeyRing", "getPassphrase", "getSigningKeyId", "clearSigningKeyId", "getHashAlgorithm", "resetHashAlgorithm"),
                org.simplejavamail.api.email.config.OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder.class,
                List.of("getRecipientPublicKeyRings", "clearRecipientPublicKeyRings", "getSymmetricAlgorithm", "resetSymmetricAlgorithm"),
                org.simplejavamail.api.email.config.OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder.class,
                List.of("getVerificationKeyRings", "clearVerificationKeyRings", "getDecryptionKeyRings", "clearDecryptionKeyRings",
                        "getPassphraseProvider", "clearPassphraseProvider"));
        companions.forEach((type, expected) -> assertThat(type.getDeclaredMethods()).as(type.getName())
                .extracting(java.lang.reflect.Method::getName).containsAll(expected));
    }

    private boolean compile(final Path source) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            final boolean compiled = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, files.getJavaFileObjects(source)).call();
            compilationDetails = diagnostics.getDiagnostics().toString();
            return compiled;
        }
    }
}
