package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliImageWorkingDirectoryTest {
    private static final String BASE = "simplejavamail.embeddedimages.dynamicresolution.base.dir";
    private static final String LOCKED_BASE = "simplejavamail.locked.embeddedimages.dynamicresolution.base.dir";

    @TempDir Path directories;

    @Test
    void requestRelativeBaseLoadsTheClientsImage() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(configuration, "images/logo.png", (byte) 1);
        image(request, "images/logo.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(), "logo.png", "--email:withEmbeddedImageBaseDir", "images"), (byte) 2);
    }

    @Test
    void noBaseKeepsUnrestrictedFileResolutionRelativeToTheClient() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(configuration, "logo.png", (byte) 1);
        image(request, "logo.png", (byte) 2);
        image(directories, "outside.png", (byte) 3);

        assertImage(execute(configuration, request, Map.of(), "logo.png"), (byte) 2);
        assertImage(execute(configuration, request, Map.of(), "../outside.png"), (byte) 3);
    }

    @Test
    void capturedRelativeBaseBelongsToTheConfigurationDirectory() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(configuration, "images/logo.png", (byte) 1);
        image(request, "images/logo.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(BASE, "images"), "logo.png"), (byte) 1);
    }

    @Test
    void configuredBaseRecognizesSourcesAlreadyPrefixedByThatBase() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(configuration, "images/logo.png", (byte) 1);
        image(request, "images/logo.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(BASE, "images"), "images/logo.png"), (byte) 1);
        assertImage(execute(configuration, request, Map.of(LOCKED_BASE, "images"), "images/logo.png",
                "--email:withEmbeddedImageBaseDir", "images"), (byte) 1);
        assertImage(execute(configuration, request, Map.of(BASE, "images"), "images/logo.png",
                "--email:withEmbeddedImageBaseDir", "images"), (byte) 2);
    }

    @Test
    void allowedOutsideFallbackUsesTheBaseOwnersDirectory() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        Files.createDirectory(configuration.resolve("images"));
        Files.createDirectory(request.resolve("images"));
        image(configuration, "outside.png", (byte) 1);
        image(request, "outside.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(BASE, "images"), "outside.png",
                "--email:allowingEmbeddedImageOutsideBaseDir", "true"), (byte) 1);
        assertImage(execute(configuration, request, Map.of(BASE, "missing"), "outside.png",
                "--email:allowingEmbeddedImageOutsideBaseDir", "true"), (byte) 1);
        assertImage(execute(configuration, request, Map.of(BASE, "images"), "outside.png",
                "--email:withEmbeddedImageBaseDir", "images", "--email:allowingEmbeddedImageOutsideBaseDir", "true"), (byte) 2);
    }

    @Test
    void equalLockedChoiceKeepsItsConfiguredOwnerAndConflictingChoiceStillFails() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(configuration, "images/logo.png", (byte) 1);
        image(request, "images/logo.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(LOCKED_BASE, "images"), "logo.png",
                "--email:withEmbeddedImageBaseDir", "images"), (byte) 1);
        final Result rejected = execute(configuration, request, Map.of(LOCKED_BASE, "images"), "logo.png",
                "--email:withEmbeddedImageBaseDir", "other");
        assertThat(rejected.execution().category()).isEqualTo(CliExitCode.COMMAND_FAILED);
        assertThat(rejected.email()).isNull();
    }

    @Test
    void absoluteBasesAndContainmentKeepTheirExistingMeaning() throws Exception {
        final Path configuration = Files.createDirectory(directories.resolve("daemon"));
        final Path request = Files.createDirectory(directories.resolve("client"));
        final Path base = Files.createDirectory(directories.resolve("approved"));
        image(base, "logo.png", (byte) 1);
        image(request, "outside.png", (byte) 2);

        assertImage(execute(configuration, request, Map.of(), "logo.png",
                "--email:withEmbeddedImageBaseDir", base.toString()), (byte) 1);
        final Result rejected = execute(configuration, request, Map.of(), request.resolve("outside.png").toString(),
                "--email:withEmbeddedImageBaseDir", base.toString());
        assertThat(rejected.execution().category()).isEqualTo(CliExitCode.COMMAND_FAILED);
        assertThat(rejected.email()).isNull();
        assertImage(execute(configuration, request, Map.of(), request.resolve("outside.png").toString(),
                "--email:withEmbeddedImageBaseDir", base.toString(), "--email:allowingEmbeddedImageOutsideBaseDir", "true"), (byte) 2);
    }

    @Test
    void fileMissesStillPermitClasspathAndUrlResolution() throws Exception {
        final Path request = Files.createDirectory(directories.resolve("client"));
        image(request, "logo.png", (byte) 6);
        assertImage(execute(directories, request, Map.of(), request.resolve("logo.png").toUri().toString(),
                "--email:withEmbeddedImageAutoResolutionForURLs", "true"), (byte) 6);

        final Result classpath = execute(directories, request, Map.of(), "/log4j2.xml",
                "--email:withEmbeddedImageAutoResolutionForClassPathResources", "true");
        assertThat(classpath.execution().exitCode()).as(classpath.execution().stderr()).isZero();
        assertThat(classpath.email().getEmbeddedImages()).hasSize(1);
        assertThat(classpath.email().getEmbeddedImages().get(0).readAllData()).containsIgnoringCase("<configuration");
    }

    @Test
    void concurrentCommandsRetainSeparateDirectories() throws Exception {
        final Path first = Files.createDirectory(directories.resolve("first"));
        final Path second = Files.createDirectory(directories.resolve("second"));
        image(first, "logo.png", (byte) 1);
        image(second, "logo.png", (byte) 2);
        final CountDownLatch bothValidating = new CountDownLatch(2);
        final Runnable awaitOtherCommand = () -> {
            bothValidating.countDown();
            try {
                assertThat(bothValidating.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var firstResult = executor.submit(() -> execute(directories, first, Map.of(), "logo.png", awaitOtherCommand));
            final var secondResult = executor.submit(() -> execute(directories, second, Map.of(), "logo.png", awaitOtherCommand));
            assertImage(firstResult.get(15, TimeUnit.SECONDS), (byte) 1);
            assertImage(secondResult.get(15, TimeUnit.SECONDS), (byte) 2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aFailedCommandReleasesItsDirectoryBeforeTheNextRequest() throws Exception {
        final Path first = Files.createDirectory(directories.resolve("first"));
        final Path second = Files.createDirectory(directories.resolve("second"));
        image(second, "logo.png", (byte) 2);
        assertThat(execute(directories, first, Map.of(), "logo.png").execution().category()).isEqualTo(CliExitCode.COMMAND_FAILED);
        assertImage(execute(directories, second, Map.of(), "logo.png"), (byte) 2);
    }

    private static Result execute(final Path configuration, final Path request, final Map<String, ?> properties,
            final String source, final String... options) {
        return execute(configuration, request, properties, source, () -> { }, options);
    }

    private static Result execute(final Path configuration, final Path request, final Map<String, ?> properties,
            final String source, final Runnable onValidation, final String... options) {
        final AtomicReference<Email> sent = new AtomicReference<>();
        final Mailer mailer = (Mailer) Proxy.newProxyInstance(Mailer.class.getClassLoader(), new Class<?>[] {Mailer.class},
                (proxy, method, values) -> {
                    if (!method.getName().equals("validate")) throw new UnsupportedOperationException(method.getName());
                    sent.set((Email) values[0]);
                    onValidation.run();
                    return null;
                });
        final MailerProvider provider = new MailerProvider() {
            @Override public Lease acquire(final CliMailerProfile profile, final java.util.function.Supplier<Mailer> factory) {
                throw new AssertionError("Validation must only request an existing Mailer");
            }
            @Override public java.util.Optional<Lease> tryAcquireExisting(final CliMailerProfile profile) {
                return java.util.Optional.of(new Lease() {
                    @Override public Mailer mailer() { return mailer; }
                    @Override public void close() { }
                });
            }
        };
        final List<String> arguments = new ArrayList<>(List.of("validate", "--email:startingBlank",
                "--email:from", "sender@example.org", "--email:to", "reader@example.org",
                "--email:withHTMLText", "<img src='" + source + "'>", "--email:withEmbeddedImageAutoResolutionForFiles", "true",
                "--email:embeddedImageAutoResolutionMustBeSuccesful", "true"));
        arguments.addAll(List.of(options));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(properties).load(), provider, configuration)) {
            return new Result(CliSupport.execute(arguments.toArray(new String[0]), request, UUID.randomUUID(), environment, null), sent.get());
        }
    }

    private static void assertImage(final Result result, final byte expected) {
        assertThat(result.execution().exitCode()).as(result.execution().stderr()).isZero();
        assertThat(result.email()).isNotNull();
        assertThat(result.email().getEmbeddedImages()).hasSize(1);
        assertThat(result.email().getEmbeddedImages().get(0).readAllBytes()).containsExactly(expected);
        assertThat(result.email().getHTMLText()).contains("src='cid:");
    }

    private static void image(final Path directory, final String relative, final byte content) throws Exception {
        final Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {content});
    }

    private record Result(CliExecutionResult execution, Email email) { }
}
