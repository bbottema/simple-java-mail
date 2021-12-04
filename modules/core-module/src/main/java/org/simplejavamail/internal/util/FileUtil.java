package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

// FileUtil uses java.nio.file, which is not available on Android < 8.0 and will throw there a NoClassDefFoundError.
// See https://developer.android.com/reference/java/nio/file/package-summary.
// Therefore, keep imports of java.nio.file.* inside this class.
public class FileUtil {
    public static String readFileContent(@NotNull final File file) throws IOException {
        return new String(readFileBytes(file), UTF_8);
    }

    public static byte[] readFileBytes(@NotNull final File file) throws IOException {
        if (!file.exists()) {
            throw new IllegalArgumentException(format("File not found: %s", file));
        }
        return Files.readAllBytes(file.toPath());
    }
}