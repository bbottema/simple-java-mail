package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;
import org.simplejavamail.config.SimpleJavaMailConfig;

import static java.util.Objects.requireNonNull;

/**
 * @see EmailPopulatingBuilderFactory
 */
public final class EmailPopulatingBuilderFactoryImpl implements EmailPopulatingBuilderFactory {
	private final SimpleJavaMailConfig config;

	public EmailPopulatingBuilderFactoryImpl(final SimpleJavaMailConfig config) {
		this.config = requireNonNull(config, "config");
	}

	@Override
	public EmailPopulatingBuilder create() {
		return new EmailPopulatingBuilderImpl(config);
	}
}
