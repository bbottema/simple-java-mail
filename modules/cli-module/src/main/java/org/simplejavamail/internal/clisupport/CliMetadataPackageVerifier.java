package org.simplejavamail.internal.clisupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Maven package gate. It checks the finished resources and starts the actual CLI in new JVMs. */
public final class CliMetadataPackageVerifier {
    private CliMetadataPackageVerifier() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected mode, package, expected CLI JAR, resource output and work directory.");
        final Path artifact = Path.of(args[1]).toAbsolutePath().normalize();
        final Path expectedJar = Path.of(args[2]).toAbsolutePath().normalize();
        final Path resources = Path.of(args[3]).toAbsolutePath().normalize();
        final Path workRoot = Path.of(args[4]).toAbsolutePath().normalize();
        final List<Path> classpath = new ArrayList<>();
        final Path cliJar;
        if ("jar".equals(args[0])) {
            cliJar = artifact;
            classpath.add(cliJar);
            for (Path path : CliMetadataInputs.classpath()) {
                if (!path.equals(resources) && !path.equals(cliJar)) classpath.add(path);
            }
        } else if ("distribution".equals(args[0])) {
            final Path configuration = artifact.resolve("etc");
            for (String resource : List.of("cli.data", "therapi.data")) {
                if (Files.exists(configuration.resolve(resource))) {
                    throw new IOException("Standalone configuration shadows packaged CLI metadata: " + resource);
                }
            }
            if (Files.isDirectory(configuration)) classpath.add(configuration);
            final Path library = artifact.resolve("lib");
            try (Stream<Path> files = Files.list(library)) {
                classpath.addAll(files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                        .sorted(Comparator.comparing(Path::toString)).toList());
            }
            final List<Path> matches = classpath.stream().filter(file -> file.getFileName().equals(expectedJar.getFileName())).toList();
            if (matches.size() != 1) throw new IOException("The distribution must contain exactly one current CLI JAR.");
            cliJar = matches.get(0);
            if (!MessageDigest.isEqual(fileDigest(expectedJar), fileDigest(cliJar))) throw new IOException("The distribution contains an outdated CLI JAR.");
            final Set<String> allowed = new HashSet<>();
            allowed.add(expectedJar.getFileName().toString());
            for (Path dependency : CliMetadataInputs.classpath()) {
                if (Files.isRegularFile(dependency)) allowed.add(dependency.getFileName().toString());
                final Path shipped = library.resolve(dependency.getFileName());
                if (Files.isRegularFile(dependency) && Files.isRegularFile(shipped)
                        && !MessageDigest.isEqual(fileDigest(dependency), fileDigest(shipped))) {
                    throw new IOException("The distribution contains an outdated dependency: " + shipped.getFileName());
                }
            }
            for (Path shipped : classpath) {
                if (Files.isRegularFile(shipped) && !allowed.contains(shipped.getFileName().toString())) {
                    throw new IOException("The distribution contains an unexpected or stale library: " + shipped.getFileName());
                }
            }
        } else {
            throw new IllegalArgumentException("Unknown package check mode: " + args[0]);
        }
        try (JarFile jar = new JarFile(cliJar.toFile())) {
            for (String name : List.of("cli.data", "therapi.data")) {
                final var entry = jar.getJarEntry(name);
                if (entry == null) throw new IOException("Packaged CLI metadata is missing: " + name);
                try (InputStream input = jar.getInputStream(entry)) {
                    if (!MessageDigest.isEqual(input.readNBytes(9 * 1024 * 1024 + 1), CliMetadataStore.readBounded(resources.resolve(name)))) {
                        throw new IOException("Packaged CLI metadata does not match this build: " + name);
                    }
                }
            }
        }
        Files.createDirectories(workRoot);
        final Path temporary = Files.createTempDirectory(workRoot, "cold-cli-");
        try {
            final Path workingDirectory = Files.createDirectory(temporary.resolve("working"));
            run(classpath, workingDirectory, temporary.resolve("help.log"), new String[] {"--daemon=off", "send", "--help"}, "--email:startingBlank");
            run(classpath, workingDirectory, temporary.resolve("subject.log"),
                    new String[] {"--daemon=off", "send", "--email:withSubject--help"}, "optional subject");
            if ("distribution".equals(args[0])) runLauncher(artifact, workingDirectory, temporary.resolve("launcher.log"));
        } finally {
            Files.walkFileTree(temporary, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        System.out.println("Packaged CLI metadata checked in fresh JVMs: " + artifact.getFileName());
    }

    private static void run(final List<Path> classpath, final Path workingDirectory, final Path log,
            final String[] arguments, final String expected) throws Exception {
        final String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        final List<String> command = new ArrayList<>();
        command.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()),
                "org.simplejavamail.cli.SimpleJavaMail"));
        command.addAll(List.of(arguments));
        final Path argumentFile = log.resolveSibling(log.getFileName() + ".args");
        final StringBuilder contents = new StringBuilder();
        for (String argument : command) {
            contents.append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        Files.writeString(argumentFile, contents, StandardCharsets.UTF_8);
        check(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "@" + argumentFile).directory(workingDirectory.toFile()), log, expected);
    }

    private static void runLauncher(final Path distribution, final Path workingDirectory, final Path log) throws Exception {
        final boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        final Path launcher = distribution.resolve(windows ? "bin/sjm.bat" : "bin/sjm");
        final List<String> command = new ArrayList<>();
        if (windows) command.addAll(List.of("cmd.exe", "/d", "/c", launcher.toString()));
        else command.addAll(List.of("sh", launcher.toString()));
        command.addAll(List.of("--daemon=off", "send", "--help"));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().remove("CLASSPATH_PREFIX");
        builder.environment().remove("JAVACMD");
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        final String pathName = builder.environment().keySet().stream().filter(name -> "PATH".equalsIgnoreCase(name)).findFirst().orElse("PATH");
        builder.environment().put(pathName, Path.of(System.getProperty("java.home"), "bin") + java.io.File.pathSeparator
                + builder.environment().getOrDefault(pathName, ""));
        check(builder, log, "--email:startingBlank");
    }

    private static void check(final ProcessBuilder builder, final Path log, final String expected) throws Exception {
        final Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        final boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly().waitFor();
        final String output = Files.readString(log);
        if (!completed || process.exitValue() != 0 || !output.contains(expected)) {
            throw new IOException("Packaged CLI startup failed: " + output.substring(0, Math.min(output.length(), 8192)));
        }
    }

    private static byte[] fileDigest(final Path file) throws IOException {
        final MessageDigest digest = CliMetadataStore.sha256();
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }
}
