package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Prevents a request UUID from executing twice during one daemon session.
 * A duplicate with identical content observes the original future; changed content under the same UUID is rejected.
 * Completed entries remain as bounded tombstones long enough to cover the protocol freshness window, and result output
 * may be discarded under memory pressure only by replacing it with an explicit ambiguous outcome, never by retrying.
 */
final class RequestLedger {
	private static final int MAX_ENTRIES = 512;
	private static final long MAX_RETAINED_RESULT_BYTES = 8L * 1024 * 1024;
	private static final long RETENTION_NANOS = Duration.ofMinutes(5).toNanos();
	private static final long MINIMUM_TOMBSTONE_NANOS = DaemonProtocol.FRESHNESS.toNanos();

	private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
	private long retainedResultBytes;

	synchronized Registration register(final DaemonRequest request) {
		pruneExpiredEntries();
		final byte[] digest = digestRequestContent(request);
		final Entry existing = entries.get(request.requestId());
		if (existing != null) {
			return registrationForDuplicate(existing, digest);
		}
		ensureCapacityForNewRequest();
		return registerNewRequest(request.requestId(), digest);
	}

	private Registration registrationForDuplicate(final Entry existing, final byte[] digest) {
		if (!MessageDigest.isEqual(existing.digest, digest)) {
			throw new IllegalArgumentException("A daemon request UUID was reused with different content");
		}
		return new Registration(false, existing.result);
	}

	private void ensureCapacityForNewRequest() {
		if (entries.size() >= MAX_ENTRIES) {
			removeOldestTerminal();
		}
		if (entries.size() >= MAX_ENTRIES) {
			throw new DaemonOverloadedException("Daemon recent-request ledger is full");
		}
	}

	private Registration registerNewRequest(final UUID requestId, final byte[] digest) {
		final Entry entry = new Entry(digest);
		entries.put(requestId, entry);
		entry.result.whenComplete((result, failure) -> retainCompletedResult(entry, result, failure));
		return new Registration(true, entry.result);
	}

	synchronized int pruneExpiredEntriesAndCount() {
		pruneExpiredEntries();
		return entries.size();
	}

	synchronized long retainedResultBytes() {
		return retainedResultBytes;
	}

	synchronized void completeIncomplete(final CliExecutionResult result) {
		for (final Entry entry : entries.values()) {
			if (entry.completedNanos == 0) {
				entry.result.complete(result);
			}
		}
	}

	private synchronized void retainCompletedResult(final Entry entry, final CliExecutionResult result,
			final Throwable failure) {
		entry.completedNanos = System.nanoTime();
		if (failure == null) {
			entry.resultBytes = encodedLength(result.stdout()) + encodedLength(result.stderr());
			retainedResultBytes += entry.resultBytes;
			discardOutputsUntilWithinLimit();
		}
	}

	private void pruneExpiredEntries() {
		final long cutoff = System.nanoTime() - RETENTION_NANOS;
		final Iterator<Entry> iterator = entries.values().iterator();
		while (iterator.hasNext()) {
			final Entry entry = iterator.next();
			if (entry.completedNanos != 0 && entry.completedNanos < cutoff) {
				retainedResultBytes -= entry.resultBytes;
				iterator.remove();
			}
		}
	}

	private void removeOldestTerminal() {
		final long safeRemovalCutoff = System.nanoTime() - MINIMUM_TOMBSTONE_NANOS;
		final Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			final Entry entry = iterator.next().getValue();
			if (entry.completedNanos != 0 && entry.completedNanos < safeRemovalCutoff) {
				retainedResultBytes -= entry.resultBytes;
				iterator.remove();
				return;
			}
		}
	}

	private void discardOutputsUntilWithinLimit() {
		if (retainedResultBytes <= MAX_RETAINED_RESULT_BYTES) {
			return;
		}
		for (final Entry entry : entries.values()) {
			if (entry.completedNanos != 0 && entry.resultBytes > 0) {
				retainedResultBytes -= entry.resultBytes;
				entry.resultBytes = 0;
				entry.result = CompletableFuture.completedFuture(new CliExecutionResult(CliExitCode.DAEMON_AMBIGUOUS, "",
						"The original daemon result is no longer retained; the request was not executed again."
								+ System.lineSeparator()));
				if (retainedResultBytes <= MAX_RETAINED_RESULT_BYTES) {
					return;
				}
			}
		}
	}

	private static int encodedLength(final String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static byte[] digestRequestContent(final DaemonRequest request) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			addLengthPrefixedValue(digest, request.operation().name());
			addLengthPrefixedValue(digest, request.workingDirectory().toString());
			for (final String argument : request.arguments()) {
				addLengthPrefixedValue(digest, argument);
			}
			return digest.digest();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static void addLengthPrefixedValue(final MessageDigest digest, final String value) {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	record Registration(boolean newlyRegistered, CompletableFuture<CliExecutionResult> result) {
	}

	/** Retains one content fingerprint and its shared terminal future for the tombstone window. */
	private static final class Entry {
		private final byte[] digest;
		private volatile CompletableFuture<CliExecutionResult> result = new CompletableFuture<>();
		private volatile long completedNanos;
		private long resultBytes;

		private Entry(final byte[] digest) {
			this.digest = digest;
		}
	}
}
