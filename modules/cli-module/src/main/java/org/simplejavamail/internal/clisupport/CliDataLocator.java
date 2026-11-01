package org.simplejavamail.internal.clisupport;

import java.io.IOException;
import java.io.InputStream;

/** Reads required packaged CLI metadata. Build generation uses explicit paths instead of this runtime reader. */
public final class CliDataLocator {
    private static final int MAX_CACHE_BYTES = 9 * 1024 * 1024;

    private CliDataLocator() {
    }

    public static byte[] readCLIDataFile() throws IOException {
        return readDataFile("cli.data");
    }

    public static byte[] readTherapiDataFile() throws IOException {
        return readDataFile("therapi.data");
    }

    private static byte[] readDataFile(final String name) throws IOException {
        try (InputStream input = CliDataLocator.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Required resource '" + name + "' is missing.");
            final byte[] bytes = input.readNBytes(MAX_CACHE_BYTES + 1);
            if (bytes.length > MAX_CACHE_BYTES) throw new IOException("CLI metadata resource exceeds its size limit.");
            return bytes;
        }
    }
}
