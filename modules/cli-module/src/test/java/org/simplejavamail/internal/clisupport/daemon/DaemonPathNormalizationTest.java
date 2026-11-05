package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonPathNormalizationTest {
    private final String originalRoot = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
    private final String originalTemporaryDirectory = System.getProperty("java.io.tmpdir");

    @AfterEach
    void restoreProperties() {
        restore(DaemonPaths.STATE_DIRECTORY_PROPERTY, originalRoot);
        restore("java.io.tmpdir", originalTemporaryDirectory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "relative-dots", "absolute-dots"})
    void longStateRootAndItsChildSpellingDeriveTheSameSocketWithoutFilesystemAccess(final String spelling) {
        final Path missing = Path.of("missing-" + UUID.randomUUID());
        final Path relative = Path.of("state-" + "a".repeat(85));
        final Path supplied = switch (spelling) {
            case "relative" -> relative;
            case "relative-dots" -> missing.resolve("..").resolve(relative);
            case "absolute-dots" -> missing.toAbsolutePath().resolve("..").resolve(relative);
            default -> throw new AssertionError(spelling);
        };
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, supplied.toString());
        final DaemonPaths parent = DaemonPaths.forInstance("normalized");
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, parent.stateRoot().toString());
        final DaemonPaths child = DaemonPaths.forInstance("normalized");

        assertThat(parent.instanceDirectory()).isEqualTo(child.instanceDirectory());
        assertThat(parent.socketPath()).isEqualTo(child.socketPath());
        assertThat(parent.socketPath().toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(96);
        assertThat(Files.exists(missing)).isFalse();
        assertThat(Files.exists(parent.stateRoot())).isFalse();
    }

    @Test
    void relativeTemporaryRootInTheChildCommandIsNormalizedWithoutFilesystemAccess() throws Exception {
        final Path temporary = Path.of("missing-temp-" + UUID.randomUUID(), "..", "temporary");
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, Path.of("state-" + "a".repeat(85)).toString());
        System.setProperty("java.io.tmpdir", temporary.toString());

        final DaemonPaths paths = DaemonPaths.forInstance("relative-temp");

        assertThat(childCommand(paths)).contains("-Djava.io.tmpdir=" + temporary.toAbsolutePath().normalize());
        assertThat(Files.exists(temporary.toAbsolutePath().normalize())).isFalse();
    }

    @Test
    void childCommandUsesTheTemporaryRootCapturedWithTheClientPaths() throws Exception {
        final Path selectedTemporaryRoot = Path.of("selected-temporary").toAbsolutePath().normalize();
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, Path.of("state-" + "a".repeat(85)).toString());
        System.setProperty("java.io.tmpdir", selectedTemporaryRoot.toString());
        final DaemonPaths paths = DaemonPaths.forInstance("captured-temp");
        System.setProperty("java.io.tmpdir", Path.of("later-temporary").toAbsolutePath().toString());

        final List<String> arguments = childCommand(paths);

        assertThat(arguments).contains("-Djava.io.tmpdir=" + selectedTemporaryRoot);
        assertThat(arguments).contains("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + paths.stateRoot());
    }

    @Test
    void shortStatePathsDoNotInterpretAnUnusedTemporaryDirectory() throws Exception {
        final Path state = Path.of("").toAbsolutePath().getRoot().resolve("daemon-" + UUID.randomUUID().toString().substring(0, 8));
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, state.toString());
        System.setProperty("java.io.tmpdir", "invalid\0temporary");

        final DaemonPaths paths = DaemonPaths.forInstance("short");

        assertThat(paths.socketPath().getParent()).isEqualTo(paths.instanceDirectory());
        assertThat(childCommand(paths)).noneMatch(argument -> argument.startsWith("-Djava.io.tmpdir="));
    }

    @Test
    void distinctRootsAndInstancesRetainDistinctShortSocketLocations() {
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, "first-" + "a".repeat(85));
        final DaemonPaths first = DaemonPaths.forInstance("first");
        final DaemonPaths otherInstance = DaemonPaths.forInstance("second");
        System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, "second-" + "a".repeat(85));
        final DaemonPaths otherRoot = DaemonPaths.forInstance("first");

        assertThat(first.socketPath()).isNotEqualTo(otherInstance.socketPath());
        assertThat(first.socketPath()).isNotEqualTo(otherRoot.socketPath());
        assertThat(first.isDerivedSocket(otherInstance.socketPath())).isFalse();
        assertThat(first.isDerivedSocket(otherRoot.socketPath())).isFalse();
    }

    private static void restore(final String name, final String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static List<String> childCommand(final DaemonPaths paths) throws Exception {
        final Method command = DaemonProcessLauncher.class.getDeclaredMethod("daemonCommand", DaemonPaths.class, String.class);
        command.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<String> arguments = (List<String>) command.invoke(null, paths, paths.instance());
        return arguments;
    }
}
