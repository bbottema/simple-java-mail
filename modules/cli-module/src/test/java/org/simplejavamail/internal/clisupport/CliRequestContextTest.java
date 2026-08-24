package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class CliRequestContextTest {
	@Test
	void concurrentRequestsResolveRelativeFilesAgainstTheirOwnCaller() {
		final Path firstRoot = Path.of("first").toAbsolutePath();
		final Path secondRoot = Path.of("second").toAbsolutePath();
		final CompletableFuture<Path> first = CompletableFuture.supplyAsync(() -> resolve(firstRoot));
		final CompletableFuture<Path> second = CompletableFuture.supplyAsync(() -> resolve(secondRoot));

		assertThat(first.join()).isEqualTo(firstRoot.resolve("body.txt").normalize());
		assertThat(second.join()).isEqualTo(secondRoot.resolve("body.txt").normalize());
	}

	private static Path resolve(final Path root) {
		try (CliRequestContext.Scope ignored = CliRequestContext.install(new CliRequestContext(UUID.randomUUID(), root))) {
			return CliRequestContext.resolveFile("body.txt").toPath();
		}
	}
}
