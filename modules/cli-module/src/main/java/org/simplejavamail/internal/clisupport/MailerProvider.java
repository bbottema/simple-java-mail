package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.Mailer;

import java.util.function.Supplier;

/**
 * Supplies command-scoped Mailer ownership without coupling command execution to the surrounding process lifecycle.
 * The profile is the compatibility boundary; a lease is the only ownership handed to command code.
 */
public interface MailerProvider extends AutoCloseable {

	@NotNull
	Lease acquire(@NotNull CliMailerProfile profile, @NotNull Supplier<Mailer> factory);

	@Override
	default void close() {
	}

	/** Keeps a Mailer available for one command and returns ownership when closed. */
	interface Lease extends AutoCloseable {
		@NotNull
		Mailer mailer();

		@Override
		void close();
	}
}
