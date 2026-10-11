package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;

/** Keeps factory-local destination identity and runtime history outside immutable configuration snapshots. */
public final class FactoryMailerRuntime {

	private final FactorySendingLimits sendingLimits;
	private final Map<String, Definition> definitions;
	private final LongSupplier clock;

	public FactoryMailerRuntime(final SimpleJavaMailConfig config) {
		this(config, new FactorySendingLimits());
	}

	FactoryMailerRuntime(final SimpleJavaMailConfig config, final FactorySendingLimits sendingLimits) {
		this(config, sendingLimits, System::nanoTime);
	}

	/** Deterministic internal clock seam; production factories share one monotonic time base, not wall-clock timestamps. */
	FactoryMailerRuntime(final SimpleJavaMailConfig config, final FactorySendingLimits sendingLimits, final LongSupplier clock) {
		this.sendingLimits = requireNonNull(sendingLimits, "sendingLimits");
		this.clock = requireNonNull(clock, "clock");
		final Map<String, SimpleJavaMailConfig> configured = config.getProperty(Property.NAMED_MAILERS);
		final Map<String, Definition> captured = new LinkedHashMap<>();
		if (configured != null) {
			configured.forEach((name, value) -> captured.put(name, new Definition(name, value, clock)));
		}
		definitions = Collections.unmodifiableMap(captured);
	}

	Definition independent(final SimpleJavaMailConfig config) {
		return new Definition(null, config, clock);
	}

	FactorySendingLimits sendingLimits() {
		return sendingLimits;
	}

	Definition named(final String name) {
		final Definition definition = definitions.get(name);
		if (definition == null) {
			throw new IllegalArgumentException("The failover Mailer has no definition in this factory's configuration. "
					+ "Add simplejavamail.mailers.<name>.smtp.host and its other settings, or correct simplejavamail.failover.mailer.");
		}
		return definition;
	}

	/** A name identifies a captured definition, not a hostname or a process-global resource. */
	static final class Definition {
		@Nullable final String name;
		final SimpleJavaMailConfig config;
		final Object privateAllowance = new Object();
		final ConnectionRecovery recovery;

		Definition(@Nullable final String name, final SimpleJavaMailConfig config) {
			this(name, config, System::nanoTime);
		}

		private Definition(@Nullable final String name, final SimpleJavaMailConfig config, final LongSupplier clock) {
			this.name = name;
			this.config = requireNonNull(config, "config");
			this.recovery = new ConnectionRecovery(clock);
		}
	}
}
