package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CliImageWorkingDirectoryProcessTest {
    @TempDir Path directories;

    @Test
    void daemonAndOneShotEmbedTheRequestImageInsteadOfTheDaemonsSameNamedFile() throws Exception {
        final Path daemonDirectory = Files.createDirectory(directories.resolve("daemon"));
        final Path requestDirectory = Files.createDirectory(directories.resolve("client"));
        image(daemonDirectory, "images/logo.png", (byte) 1);
        image(requestDirectory, "images/logo.png", (byte) 2);
        image(daemonDirectory, "logo.png", (byte) 3);
        image(requestDirectory, "logo.png", (byte) 4);
        image(daemonDirectory, "daemon-only.png", (byte) 5);
        final Wiser smtp = Wiser.create(SMTPServer.port(0));
        smtp.start();
        boolean daemonStarted = false;
        try {
            assertSuccess(invoke(daemonDirectory, Map.of("simplejavamail.embeddedimages.dynamicresolution.enable.dir", "true"),
                    "daemon", "start", "--daemon-instance=images"));
            daemonStarted = true;
            for (String mode : List.of("off", "require")) {
                assertSuccess(invoke(requestDirectory, Map.of(), send(mode, smtp.getServer().getPortAllocated(), true)));
                assertSuccess(invoke(requestDirectory, Map.of(), send(mode, smtp.getServer().getPortAllocated(), false)));
            }
            assertThat(smtp.getMessages()).hasSize(4);
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            for (int i = 0; i < 4; i++) {
                final Email received = mail.converter().fromMimeMessage(smtp.getMessages().get(i).getMimeMessage()).toEmail();
                assertThat(received.getEmbeddedImages()).hasSize(1);
                assertThat(received.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) (i % 2 == 0 ? 2 : 4));
            }
            final List<String> optional = new ArrayList<>(List.of(send("require", smtp.getServer().getPortAllocated(), false)));
            optional.addAll(List.of("--email:withHTMLText", "<img src='daemon-only.png'>",
                    "--email:embeddedImageAutoResolutionMustBeSuccesful", "false"));
            assertSuccess(invoke(requestDirectory, Map.of(), optional.toArray(new String[0])));
            final Email unresolved = mail.converter().fromMimeMessage(smtp.getMessages().get(4).getMimeMessage()).toEmail();
            assertThat(unresolved.getEmbeddedImages()).isEmpty();
            assertThat(unresolved.getHTMLText()).contains("src='daemon-only.png'");
        } finally {
            try {
                if (daemonStarted) assertSuccess(invoke(daemonDirectory, Map.of(), "daemon", "stop", "--daemon-instance=images"));
            } finally {
                smtp.stop();
            }
        }
    }

    private static String[] send(final String mode, final int port, final boolean withBase) {
        final List<String> arguments = new ArrayList<>(List.of("send", "--daemon=" + mode, "--daemon-instance=images",
                "--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "reader@example.org",
                "--email:withHTMLText", "<img src='logo.png'>", "--email:withEmbeddedImageAutoResolutionForFiles", "true",
                "--email:embeddedImageAutoResolutionMustBeSuccesful", "true",
                "--mailer:withSMTPServer", "127.0.0.1", Integer.toString(port), "--mailer:withConnectionPoolCoreSize", "0"));
        if (withBase) arguments.addAll(List.of("--email:withEmbeddedImageBaseDir", "images"));
        return arguments.toArray(new String[0]);
    }

    private Invocation invoke(final Path workingDirectory, final Map<String, String> properties, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
        command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + directories.resolve("state"));
        command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true");
        command.add("-Dsimplejavamail.cli.daemon.console-child=true");
        properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
        final String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        command.addAll(List.of("-cp", classpath, "org.simplejavamail.cli.SimpleJavaMail"));
        command.addAll(List.of(arguments));
        final Path output = Files.createTempFile(directories, "cli-", ".log");
        final Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("CLI process completed").isTrue();
            return new Invocation(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned CLI process stopped").isTrue();
            }
        }
    }

    private static void image(final Path directory, final String relative, final byte content) throws Exception {
        final Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {content});
    }

    private static void assertSuccess(final Invocation invocation) {
        assertThat(invocation.exitCode()).as(invocation.output()).isZero();
    }

    private record Invocation(int exitCode, String output) { }
}
