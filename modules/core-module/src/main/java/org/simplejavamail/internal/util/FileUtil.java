package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

// java.nio.file is unavailable on Android < 8.0. The legacy File entry points remain, but the 10.x Path conveniences require an NIO-capable runtime.
// See https://developer.android.com/reference/java/nio/file/package-summary.
public class FileUtil {
	public static String readFileContent(@NotNull final File file) throws IOException {
		return readFileContent(file.toPath());
	}

	public static String readFileContent(@NotNull final Path path) throws IOException {
		return new String(readFileBytes(path), UTF_8);
	}

	public static byte[] readFileBytes(@NotNull final File file) throws IOException {
		return readFileBytes(file.toPath());
	}

	public static byte[] readFileBytes(@NotNull final Path path) throws IOException {
		if (!Files.exists(path)) {
			throw new IllegalArgumentException(format("File not found: %s", path));
		}
		return Files.readAllBytes(path);
	}

	public static void writeFileBytes(@NotNull final File file, final byte[] bytes) throws IOException {
		try {
			Files.createFile(file.toPath());
		} catch (FileAlreadyExistsException e) {
			// ignore
		}
		Files.write(file.toPath(), bytes);
	}
}
