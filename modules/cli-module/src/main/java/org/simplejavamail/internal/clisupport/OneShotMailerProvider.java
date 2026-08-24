package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.Mailer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Preserves the original CLI rule that every one-shot command closes its own Mailer. */
public final class OneShotMailerProvider implements MailerProvider {
	@Override
	public Lease acquire(@NotNull final CliMailerProfile profile, @NotNull final Supplier<Mailer> factory) {
		final Mailer mailer = factory.get();
		return new Lease() {
			private final AtomicBoolean closed = new AtomicBoolean();

			@Override
			public Mailer mailer() {
				return mailer;
			}

			@Override
			public void close() {
				if (closed.compareAndSet(false, true)) {
					try {
						mailer.close();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("Interrupted while closing the one-shot Mailer", e);
					} catch (Exception e) {
						throw new IllegalStateException("Unable to close the one-shot Mailer", e);
					}
				}
			}
		};
	}
}
