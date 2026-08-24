package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.cli.SimpleJavaMail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CliJavaBaselineTest {

	@Test
	void allCliProductionClassesUseJava17Bytecode() throws IOException, URISyntaxException {
		final Path classes = Path.of(SimpleJavaMail.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertThat(classes).isDirectory();
		final List<Path> classFiles;
		try (Stream<Path> files = Files.walk(classes)) {
			classFiles = files.filter(path -> path.getFileName().toString().endsWith(".class")).toList();
		}
		assertThat(classFiles).isNotEmpty();
		for (Path classFile : classFiles) {
			assertThat(classFileMajorVersion(classFile)).as(classFile.toString()).isEqualTo(61);
		}
	}

	@Test
	void consumedLibraryProductionClassesRemainJava8Bytecode() throws IOException {
		assertThat(classFileMajorVersion(org.simplejavamail.api.SimpleJavaMail.class)).isEqualTo(52);
		assertThat(classFileMajorVersion(org.simplejavamail.api.mailer.Mailer.class)).isEqualTo(52);
	}

	private static int classFileMajorVersion(final Class<?> type) throws IOException {
		final String resourceName = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream stream = type.getResourceAsStream(resourceName);
			 DataInputStream input = new DataInputStream(stream)) {
			assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
			input.readUnsignedShort();
			return input.readUnsignedShort();
		}
	}

	private static int classFileMajorVersion(final Path classFile) throws IOException {
		try (InputStream stream = Files.newInputStream(classFile);
			 DataInputStream input = new DataInputStream(stream)) {
			assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
			input.readUnsignedShort();
			return input.readUnsignedShort();
		}
	}
}
