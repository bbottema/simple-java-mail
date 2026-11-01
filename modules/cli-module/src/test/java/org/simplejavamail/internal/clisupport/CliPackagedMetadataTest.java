package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.JavadocCacheSerialization;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CliPackagedMetadataTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"missing-cli", "missing-both", "bad-cli", "oversized-cli", "io-cli", "missing-therapi", "bad-therapi", "incomplete-therapi"})
    void unusablePackagedMetadataFailsClearlyWithoutRuntimeDiscovery(final String mode) throws Exception {
        final Result result = run(mode);
        assertThat(result.exitCode).as(result.output).isEqualTo(2);
        assertThat(result.output).contains("CLI metadata", mode.contains("therapi") ? "therapi.data" : "cli.data");
        assertThat(result.output).doesNotContain("Runtime Javadoc lookup attempted", "ExceptionInInitializerError", "NoClassDefFoundError");
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "old-regeneration-property", "source-checkout"})
    void validPackagedDataWorksWithoutSourceJavadocsOrDevelopmentDirectoryRules(final String mode) throws Exception {
        final Result result = run(mode);
        assertThat(result.exitCode).as(result.output).isZero();
        assertThat(result.output).contains("--email:startingBlank", "--mailer:withSMTPServer");
        assertThat(result.output).doesNotContain("Runtime Javadoc lookup attempted", "regenerating", "Generating the internal");
        if ("source-checkout".equals(mode)) {
            assertThat(Files.readString(directory.resolve("src/main/resources/cli.data"))).isEqualTo("leave this project file alone");
            assertThat(Files.readString(directory.resolve("src/main/resources/therapi.data"))).isEqualTo("leave this project file alone");
        }
    }

    private Result run(final String mode) throws Exception {
        if ("source-checkout".equals(mode)) {
            Files.createDirectories(directory.resolve("src/test/resources"));
            Files.writeString(directory.resolve("src/test/resources/log4j2.xml"), "marker");
            Files.createDirectories(directory.resolve("src/main/resources"));
            Files.writeString(directory.resolve("src/main/resources/cli.data"), "leave this project file alone");
            Files.writeString(directory.resolve("src/main/resources/therapi.data"), "leave this project file alone");
        }
        if ("incomplete-therapi".equals(mode)) {
            Files.write(directory.resolve("incomplete.data"), CliMetadataCache.wrap(CliMetadataCache.Kind.THERAPI_JAVADOC,
                    CliSourceApiFingerprint.calculate(), JavadocCacheSerialization.serialize(Map.of())));
        }
        final String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        if ("old-regeneration-property".equals(mode)) command.add("-Dsimplejavamail.cli.metadata.regenerate=true");
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode, directory.toString()));
        final Path log = directory.resolve("process.log");
        final Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        final boolean completed = process.waitFor(45, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly().waitFor();
        final String output = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(completed).as(output).isTrue();
        return new Result(process.exitValue(), output);
    }

    private static final class Result {
        final int exitCode;
        final String output;
        Result(final int exitCode, final String output) { this.exitCode = exitCode; this.output = output; }
    }

    public static final class Probe {
        public static void main(final String[] args) throws Exception {
            final String[] entries = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator));
            final URL[] urls = new URL[entries.length];
            for (int index = 0; index < entries.length; index++) urls[index] = Path.of(entries[index]).toUri().toURL();
            try (ResourceLoader loader = new ResourceLoader(urls, args[0], Path.of(args[1]))) {
                Thread.currentThread().setContextClassLoader(loader);
                final Class<?> bootstrap = Class.forName("org.simplejavamail.internal.clisupport.daemon.DaemonBootstrap", true, loader);
                final Method execute = bootstrap.getMethod("runProcess", String[].class);
                final int code = (Integer) execute.invoke(null, (Object) new String[] {"send", "--help"});
                final int optionHelp = (Integer) execute.invoke(null, (Object) new String[] {"send", "--email:withSubject--help"});
                if (code != optionHelp) throw new AssertionError("Standard and generated help disagree about packaged metadata");
                final int management = (Integer) execute.invoke(null, (Object) new String[] {"daemon", "--help"});
                if (management != 0) throw new AssertionError("Thin daemon help must not need metadata");
                if (loader.javadocReads != 0) throw new AssertionError("Runtime Javadoc lookup attempted");
                System.exit(code);
            }
        }
    }

    private static final class ResourceLoader extends URLClassLoader {
        private final String mode;
        private final Path directory;
        private int javadocReads;
        ResourceLoader(final URL[] urls, final String mode, final Path directory) {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.mode = mode;
            this.directory = directory;
        }
        @Override public URL getResource(final String name) {
            forbidSourceDocumentation(name);
            return selected(name) ? null : super.getResource(name);
        }
        @Override public InputStream getResourceAsStream(final String name) {
            forbidSourceDocumentation(name);
            if (!selected(name)) return super.getResourceAsStream(name);
            if (mode.startsWith("missing")) return null;
            if (mode.startsWith("io")) return new InputStream() {
                @Override public int read() throws IOException { throw new IOException("Unreadable packaged resource"); }
            };
            if (mode.startsWith("oversized")) return new ByteArrayInputStream(new byte[9 * 1024 * 1024 + 1]);
            if (mode.startsWith("incomplete")) {
                try { return Files.newInputStream(directory.resolve("incomplete.data")); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
            return new ByteArrayInputStream(new byte[0]);
        }
        private boolean selected(final String name) {
            if ("missing-both".equals(mode)) return "cli.data".equals(name) || "therapi.data".equals(name);
            return mode.endsWith("-cli") ? "cli.data".equals(name) : mode.endsWith("-therapi") && "therapi.data".equals(name);
        }
        private void forbidSourceDocumentation(final String name) {
            if (name.endsWith("__Javadoc.json")) {
                javadocReads++;
                throw new AssertionError("Runtime Javadoc lookup attempted: " + name);
            }
        }
    }
}
