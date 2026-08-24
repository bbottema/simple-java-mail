package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonProtocolTest {
	@Test
	void requestRoundTripsThroughOneByteReadsAndWrites() throws Exception {
		final UUID session = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final byte[] secret = new byte[32];
		java.util.Arrays.fill(secret, (byte) 7);
		final DaemonRequest request = new DaemonRequest(DaemonOperation.EXECUTE, session, requestId, Instant.now(),
				Path.of("folder with spaces", "ü"), List.of("send", "", "--leading", "@literal", "line\nfeed"));
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DaemonProtocol.writeRequest(new FragmentedWritable(bytes), request, secret);

		final DaemonRequest decoded = DaemonProtocol.readRequest(new FragmentedReadable(bytes.toByteArray()), session, secret);
		assertThat(decoded.operation()).isEqualTo(DaemonOperation.EXECUTE);
		assertThat(decoded.requestId()).isEqualTo(requestId);
		assertThat(decoded.workingDirectory()).isEqualTo(request.workingDirectory());
		assertThat(decoded.arguments()).containsExactlyElementsOf(request.arguments());
	}

	@Test
	void responseIsAuthenticatedAndTamperingIsRejected() throws Exception {
		final UUID session = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final byte[] secret = new byte[32];
		final DaemonResponse response = new DaemonResponse(session, requestId, Instant.now(), CliExitCode.DAEMON_BUSY,
				"out", "err", "10.0.0");
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DaemonProtocol.writeResponse(new FragmentedWritable(bytes), response, secret);
		final byte[] frame = bytes.toByteArray();
		frame[frame.length - 1] ^= 1;

		assertThatThrownBy(() -> DaemonProtocol.readResponse(new FragmentedReadable(frame), session, requestId, secret))
				.isInstanceOf(DaemonProtocolException.class)
				.extracting(error -> ((DaemonProtocolException) error).kind())
				.isEqualTo(DaemonProtocolException.Kind.AUTHENTICATION);
	}

	@Test
	void invalidLengthIsRejectedBeforePayloadAllocation() throws Exception {
		final UUID session = UUID.randomUUID();
		final byte[] secret = new byte[32];
		final DaemonRequest request = new DaemonRequest(DaemonOperation.STATUS, session, UUID.randomUUID(), Instant.now(),
				Path.of("."), List.of());
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DaemonProtocol.writeRequest(new FragmentedWritable(bytes), request, secret);
		final byte[] frame = bytes.toByteArray();
		ByteBuffer.wrap(frame, 9, Integer.BYTES).putInt(Integer.MAX_VALUE);

		assertThatThrownBy(() -> DaemonProtocol.readRequest(new FragmentedReadable(frame), session, secret))
				.isInstanceOf(DaemonProtocolException.class)
				.hasMessageContaining("length");
	}

	@Test
	void authenticatedButStaleRequestIsRejected() throws Exception {
		final UUID session = UUID.randomUUID();
		final byte[] secret = new byte[32];
		final DaemonRequest request = new DaemonRequest(DaemonOperation.STATUS, session, UUID.randomUUID(),
				Instant.now().minus(DaemonProtocol.FRESHNESS.multipliedBy(2)), Path.of("."), List.of());
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DaemonProtocol.writeRequest(new FragmentedWritable(bytes), request, secret);

		assertThatThrownBy(() -> DaemonProtocol.readRequest(new FragmentedReadable(bytes.toByteArray()), session, secret))
				.isInstanceOf(DaemonProtocolException.class)
				.extracting(error -> ((DaemonProtocolException) error).kind())
				.isEqualTo(DaemonProtocolException.Kind.STALE);
	}

	@Test
	void aggregateRequestSizeIsRejectedBeforeAnOversizedFrameCanBeWritten() {
		final UUID session = UUID.randomUUID();
		final String maximumArgument = "x".repeat(DaemonProtocol.MAX_ARGUMENT_BYTES);
		final DaemonRequest request = new DaemonRequest(DaemonOperation.EXECUTE, session, UUID.randomUUID(), Instant.now(),
				Path.of("."), java.util.Collections.nCopies(17, maximumArgument));

		assertThatThrownBy(() -> DaemonProtocol.writeRequest(
				new FragmentedWritable(new ByteArrayOutputStream()), request, new byte[32]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frame is too large");
	}

	private static final class FragmentedWritable implements WritableByteChannel {
		private final ByteArrayOutputStream target;
		private boolean open = true;

		private FragmentedWritable(final ByteArrayOutputStream target) { this.target = target; }
		@Override public int write(final ByteBuffer source) { target.write(source.get()); return 1; }
		@Override public boolean isOpen() { return open; }
		@Override public void close() { open = false; }
	}

	private static final class FragmentedReadable implements ReadableByteChannel {
		private final ByteArrayInputStream source;
		private boolean open = true;

		private FragmentedReadable(final byte[] source) { this.source = new ByteArrayInputStream(source); }
		@Override public int read(final ByteBuffer target) {
			final int value = source.read();
			if (value < 0) return -1;
			target.put((byte) value);
			return 1;
		}
		@Override public boolean isOpen() { return open; }
		@Override public void close() { open = false; }
	}
}
