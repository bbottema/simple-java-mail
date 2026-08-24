package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Locates, reads, and conditionally persists generated CLI metadata across development and packaged runtimes.
 * Development builds update source resources and exploded classpath copies; packaged archives remain read-only and use
 * classpath resources. All reads and writes enforce one shared size limit before metadata decoding.
 */
public class CliDataLocator {
	private static final int MAX_CACHE_BYTES = 9 * 1024 * 1024;

	public static String locateCLIDataFile() {
		return locateDataFile("cli.data");
	}

	public static String locateTherapiDataFile() {
		return locateDataFile("therapi.data");
	}

	public static byte[] readCLIDataFile() throws IOException {
		return readDataFile("cli.data");
	}

	public static byte[] readTherapiDataFile() throws IOException {
		return readDataFile("therapi.data");
	}

	public static boolean persistCLIDataFile(final byte[] data) throws IOException {
		return persistDataFile("cli.data", data);
	}

	public static boolean persistTherapiDataFile(final byte[] data) throws IOException {
		return persistDataFile("therapi.data", data);
	}

	@NotNull
	private static String locateDataFile(final String dataFileName) {
		final Path developmentFile = developmentFile(dataFileName);
		if (developmentFile != null) {
			return developmentFile.toString();
		}
		final URL resource = CliDataLocator.class.getClassLoader().getResource(dataFileName);
		if (resource != null && "file".equals(resource.getProtocol())) {
			try {
				return Path.of(resource.toURI()).toString();
			} catch (URISyntaxException e) {
				throw new IllegalStateException("Unable to locate CLI metadata resource", e);
			}
		}
		throw new IllegalStateException("CLI metadata is packaged as a read-only classpath resource");
	}

	private static byte[] readDataFile(final String dataFileName) throws IOException {
		final Path developmentFile = developmentFile(dataFileName);
		if (developmentFile != null && Files.isRegularFile(developmentFile)) {
			try (InputStream input = Files.newInputStream(developmentFile)) {
				return readBounded(input);
			}
		}
		try (InputStream input = CliDataLocator.class.getClassLoader().getResourceAsStream(dataFileName)) {
			if (input == null) {
				return null;
			}
			return readBounded(input);
		}
	}

	private static boolean persistDataFile(final String dataFileName, final byte[] data) throws IOException {
		requireCacheSizeWithinLimit(data);
		final Set<Path> targets = new LinkedHashSet<>();
		final Path developmentFile = developmentFile(dataFileName);
		if (developmentFile != null) {
			targets.add(developmentFile.toAbsolutePath().normalize());
		}
		final URL resource = CliDataLocator.class.getClassLoader().getResource(dataFileName);
		if (resource != null && "file".equals(resource.getProtocol())) {
			try {
				targets.add(Path.of(resource.toURI()).toAbsolutePath().normalize());
			} catch (URISyntaxException e) {
				throw new IOException("Unable to locate generated CLI metadata output", e);
			}
		}
		for (final Path target : targets) {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
		}
		return !targets.isEmpty();
	}

	private static Path developmentFile(final String dataFileName) {
		if (new File("src/test/resources/log4j2.xml").exists()) {
			return Path.of("src/main/resources", dataFileName);
		}
		if (new File("modules/cli-module/src/test/resources/log4j2.xml").exists()) {
			return Path.of("modules/cli-module/src/main/resources", dataFileName);
		}
		return null;
	}

	private static byte[] requireCacheSizeWithinLimit(final byte[] data) throws IOException {
		if (data.length > MAX_CACHE_BYTES) {
			throw new IOException("CLI metadata resource exceeds its size limit");
		}
		return data;
	}

	private static byte[] readBounded(final InputStream input) throws IOException {
		return requireCacheSizeWithinLimit(input.readNBytes(MAX_CACHE_BYTES + 1));
	}
}
