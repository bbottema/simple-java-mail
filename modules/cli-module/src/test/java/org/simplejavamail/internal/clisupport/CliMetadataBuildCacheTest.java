package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliMetadataBuildCacheTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);
    @TempDir Path directory;

    private CliGeneratedMetadata metadata() throws Exception {
        return CliGeneratedMetadata.builder().options(CliDataLocator.readCLIDataFile()).javadoc(CliDataLocator.readTherapiDataFile()).build();
    }

    @Test
    void firstBuildGeneratesThenUnchangedAndCleanOutputsReuseWithoutGeneration() throws Exception {
        final Path cache = directory.resolve("cache");
        final Path output = directory.resolve("target/classes");
        final CliMetadataStore store = new CliMetadataStore(cache, output);
        final CliGeneratedMetadata pair = metadata();
        assertThat(store.ensure(FIRST, false, () -> pair)).isTrue();
        final FileTime time = FileTime.fromMillis(123456000);
        Files.setLastModifiedTime(output.resolve("cli.data"), time);
        assertThat(store.ensure(FIRST, false, () -> { throw new AssertionError("Generator must not run"); })).isFalse();
        assertThat(Files.getLastModifiedTime(output.resolve("cli.data"))).isEqualTo(time);
        Files.delete(output.resolve("cli.data"));
        Files.delete(output.resolve("therapi.data"));
        Files.delete(output);
        assertThat(store.ensure(FIRST, false, () -> { throw new AssertionError("Clean output must reuse the persistent entry"); })).isFalse();
        assertThat(Files.readAllBytes(output.resolve("cli.data"))).containsExactly(pair.getOptions());
        assertThat(Files.readAllBytes(output.resolve("therapi.data"))).containsExactly(pair.getJavadoc());
    }

    @Test
    void changedInputsGenerateAndReturningToOldInputsReusesTheirEntry() throws Exception {
        final CliMetadataStore store = new CliMetadataStore(directory.resolve("cache"), directory.resolve("output"));
        final CliGeneratedMetadata pair = metadata();
        final AtomicInteger generations = new AtomicInteger();
        final CliMetadataStore.Generator generator = () -> { generations.incrementAndGet(); return pair; };
        assertThat(store.ensure(FIRST, false, generator)).isTrue();
        assertThat(store.ensure(SECOND, false, generator)).isTrue();
        assertThat(store.ensure(FIRST, false, generator)).isFalse();
        assertThat(generations).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cli.data", "therapi.data", "manifest.properties"})
    void aMissingOrDamagedPartIsRebuiltByTheBuild(final String name) throws Exception {
        final Path cache = directory.resolve("cache");
        final CliMetadataStore store = new CliMetadataStore(cache, directory.resolve("output"));
        final CliGeneratedMetadata pair = metadata();
        store.ensure(FIRST, false, () -> pair);
        Files.delete(cache.resolve(FIRST).resolve(name));
        assertThat(store.ensure(FIRST, false, () -> pair)).isTrue();
        Files.writeString(cache.resolve(FIRST).resolve(name), "damaged");
        assertThat(store.ensure(FIRST, false, () -> pair)).isTrue();
        assertThat(store.ensure(FIRST, false, () -> { throw new AssertionError("Repaired entry must be reusable"); })).isFalse();
    }

    @Test
    void aForcedReleaseBuildGeneratesEvenWhenTheEntryIsValid() throws Exception {
        final CliMetadataStore store = new CliMetadataStore(directory.resolve("cache"), directory.resolve("output"));
        final CliGeneratedMetadata pair = metadata();
        store.ensure(FIRST, false, () -> pair);
        final AtomicInteger generations = new AtomicInteger();
        assertThat(store.ensure(FIRST, true, () -> { generations.incrementAndGet(); return pair; })).isTrue();
        assertThat(generations).hasValue(1);
    }

    @Test
    void failedGenerationCannotReplaceAValidCompletedEntry() throws Exception {
        final CliMetadataStore store = new CliMetadataStore(directory.resolve("cache"), directory.resolve("output"));
        final CliGeneratedMetadata pair = metadata();
        store.ensure(FIRST, false, () -> pair);
        assertThatThrownBy(() -> store.ensure(FIRST, true,
                () -> CliGeneratedMetadata.builder().options(new byte[0]).javadoc(new byte[0]).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.ensure(FIRST, false, () -> { throw new AssertionError("Previous completed entry was lost"); })).isFalse();
    }

    @Test
    void keysUseJavadocAndGeneratorContentsButIgnoreFileDatesAndGeneratedOutput() throws Exception {
        final Path classes = Files.createDirectories(directory.resolve("classes"));
        final Path code = Files.writeString(classes.resolve("Generator.class"), "code");
        final Path docs = Files.writeString(classes.resolve("Api__Javadoc.json"), "original documentation");
        final Path pom = Files.writeString(directory.resolve("pom.xml"), "dependency-version-one");
        final String first = CliMetadataInputs.key(List.of(classes), List.of(pom));
        Files.setLastModifiedTime(code, FileTime.fromMillis(123456000));
        Files.writeString(classes.resolve("cli.data"), "new output");
        Files.writeString(classes.resolve("manifest.properties"), "new output manifest");
        assertThat(CliMetadataInputs.key(List.of(classes), List.of(pom))).isEqualTo(first);
        Files.writeString(docs, "changed documentation only");
        assertThat(CliMetadataInputs.key(List.of(classes), List.of(pom))).isNotEqualTo(first);
        Files.writeString(docs, "original documentation");
        Files.writeString(code, "changed generator");
        assertThat(CliMetadataInputs.key(List.of(classes), List.of(pom))).isNotEqualTo(first);
        Files.writeString(code, "code");
        Files.writeString(pom, "dependency-version-two");
        assertThat(CliMetadataInputs.key(List.of(classes), List.of(pom))).isNotEqualTo(first);
    }

    @Test
    void directoryAndJarInputsWithDifferentArchiveDatesAndOrderHaveTheSameKey() throws Exception {
        final Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "code");
        Files.writeString(classes.resolve("A__Javadoc.json"), "documentation");
        final Path first = directory.resolve("first.jar");
        final Path second = directory.resolve("second.jar");
        writeJar(first, List.of("A.class", "A__Javadoc.json"), 123456000);
        writeJar(second, List.of("A__Javadoc.json", "A.class"), 987654000);
        assertThat(CliMetadataInputs.key(List.of(first), List.of()))
                .isEqualTo(CliMetadataInputs.key(List.of(second), List.of()))
                .isEqualTo(CliMetadataInputs.key(List.of(classes), List.of()));
    }

    private static void writeJar(final Path file, final List<String> order, final long time) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file))) {
            for (String name : order) {
                final JarEntry entry = new JarEntry(name);
                entry.setTime(time);
                jar.putNextEntry(entry);
                jar.write((name.endsWith(".class") ? "code" : "documentation").getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    @Test
    void dependencyVersionsMatterButMavenPropertyCommentsAndOrderDoNot() throws Exception {
        final Path first = directory.resolve("first.jar");
        final Path reordered = directory.resolve("reordered.jar");
        final Path changed = directory.resolve("changed.jar");
        writeVersionJar(first, "# first build date\nversion=1\nartifactId=helper\ngroupId=example\n");
        writeVersionJar(reordered, "# another build date\ngroupId=example\nartifactId=helper\nversion=1\n");
        writeVersionJar(changed, "groupId=example\nartifactId=helper\nversion=2\n");
        assertThat(CliMetadataInputs.key(List.of(first), List.of()))
                .isEqualTo(CliMetadataInputs.key(List.of(reordered), List.of()))
                .isNotEqualTo(CliMetadataInputs.key(List.of(changed), List.of()));
    }

    private static void writeVersionJar(final Path file, final String properties) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry("META-INF/maven/example/helper/pom.properties"));
            jar.write(properties.getBytes(StandardCharsets.ISO_8859_1));
            jar.closeEntry();
        }
    }
}
