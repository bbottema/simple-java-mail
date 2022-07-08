/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
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

    public static void writeFileBytes(@NotNull final File file, final byte[] bytes) throws IOException {
        try {
            Files.createFile(file.toPath());
        } catch (FileAlreadyExistsException e) {
            // ignore
        }
        Files.write(file.toPath(), bytes);
    }
}
