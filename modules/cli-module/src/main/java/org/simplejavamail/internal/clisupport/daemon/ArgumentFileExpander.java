package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Expands Picocli-compatible {@code @argument} files before daemon routing.
 * Expansion deliberately happens in the short-lived client so relative paths use the caller's working directory.
 * Nesting, cycles, UTF-8 decoding, file size, argument count, and aggregate argument sizes are bounded before any
 * request can reach the daemon.
 */
final class ArgumentFileExpander {
	private static final int MAX_DEPTH = 4;
	private static final int MAX_ARGUMENTS = 512;
	private static final int MAX_FILE_BYTES = 64 * 1024;
	private static final int MAX_ARGUMENT_BYTES = 64 * 1024;

	private ArgumentFileExpander() {
	}

	static String[] expand(final String[] arguments, final Path workingDirectory) {
		final List<String> expanded = new ArrayList<>();
		expandInto(List.of(arguments), workingDirectory.toAbsolutePath().normalize(), 0, new HashSet<>(), expanded);
		return expanded.toArray(new String[0]);
	}

	private static void expandInto(final List<String> arguments, final Path workingDirectory, final int depth,
			final Set<Path> activeFiles, final List<String> target) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException("Argument-file nesting exceeds " + MAX_DEPTH);
		}
		for (final String argument : arguments) {
			expandArgument(argument, workingDirectory, depth, activeFiles, target);
		}
	}

	private static void expandArgument(final String argument, final Path workingDirectory, final int depth,
			final Set<Path> activeFiles, final List<String> target) {
		if (argument.startsWith("@@")) {
			addArgument(argument.substring(1), target);
			return;
		}
		if (!argument.startsWith("@") || argument.length() == 1) {
			addArgument(argument, target);
			return;
		}
		final Path file = workingDirectory.resolve(argument.substring(1)).normalize().toAbsolutePath();
		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			addArgument(argument, target);
			return;
		}
		expandArgumentFile(file, workingDirectory, depth, activeFiles, target);
	}

	private static void expandArgumentFile(final Path file, final Path workingDirectory, final int depth,
			final Set<Path> activeFiles, final List<String> target) {
		if (!activeFiles.add(file)) {
			throw new IllegalArgumentException("Recursive argument file: " + file.getFileName());
		}
		try {
			expandInto(tokenize(readArgumentFile(file)), workingDirectory, depth + 1, activeFiles, target);
		} catch (IOException e) {
			throw new IllegalArgumentException("Unable to read argument file: " + file.getFileName(), e);
		} finally {
			activeFiles.remove(file);
		}
	}

	private static String readArgumentFile(final Path file) throws IOException {
		final byte[] bytes;
		try (InputStream input = Files.newInputStream(file)) {
			bytes = input.readNBytes(MAX_FILE_BYTES + 1);
		}
		if (bytes.length > MAX_FILE_BYTES) {
			throw new IllegalArgumentException("Argument file exceeds " + MAX_FILE_BYTES + " bytes");
		}
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
	}

	private static void addArgument(final String argument, final List<String> target) {
		if (argument.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
			throw new IllegalArgumentException("Command argument exceeds " + MAX_ARGUMENT_BYTES + " bytes");
		}
		target.add(argument);
		if (target.size() > MAX_ARGUMENTS) {
			throw new IllegalArgumentException("Expanded command exceeds " + MAX_ARGUMENTS + " arguments");
		}
	}

	static List<String> tokenize(final String content) {
		if (Boolean.getBoolean("picocli.useSimplifiedAtFiles")) {
			return tokenizeSimplified(content);
		}
		final List<String> result = new ArrayList<>();
		final StringBuilder token = new StringBuilder();
		char quote = 0;
		boolean escaped = false;
		boolean comment = false;
		boolean tokenStarted = false;
		for (int i = 0; i < content.length(); i++) {
			final char character = content.charAt(i);
			if (comment) {
				if (character == '\n' || character == '\r') {
					comment = false;
				}
				continue;
			}
			if (escaped) {
				token.append(character);
				escaped = false;
				tokenStarted = true;
			} else if (character == '\\' && i + 1 < content.length()
					&& (content.charAt(i + 1) == '\\'
					|| quote == 0 && (content.charAt(i + 1) == '"' || content.charAt(i + 1) == '\'')
					|| quote != 0 && content.charAt(i + 1) == quote)) {
				escaped = true;
				tokenStarted = true;
			} else if (quote == 0 && (character == '"' || character == '\'')) {
				quote = character;
				tokenStarted = true;
			} else if (character == quote) {
				quote = 0;
				tokenStarted = true;
			} else if (quote == 0 && character == '#') {
				comment = true;
			} else if (quote == 0 && Character.isWhitespace(character)) {
				if (tokenStarted) {
					result.add(token.toString());
					token.setLength(0);
					tokenStarted = false;
				}
			} else {
				token.append(character);
				tokenStarted = true;
			}
		}
		if (escaped || quote != 0) {
			throw new IllegalArgumentException("Unterminated escape or quote in argument file");
		}
		if (tokenStarted) {
			result.add(token.toString());
		}
		return result;
	}

	private static List<String> tokenizeSimplified(final String content) {
		final List<String> result = new ArrayList<>();
		for (final String line : content.split("\\R", -1)) {
			if (!line.isEmpty() && !line.trim().startsWith("#")) {
				result.add(line);
			}
		}
		return result;
	}
}
