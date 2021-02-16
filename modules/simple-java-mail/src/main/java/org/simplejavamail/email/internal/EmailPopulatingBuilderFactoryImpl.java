package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;

/**
 * @see EmailPopulatingBuilderFactory
 */
public final class EmailPopulatingBuilderFactoryImpl implements EmailPopulatingBuilderFactory {
	@Override
	public EmailPopulatingBuilder create() {
		return 	new EmailPopulatingBuilderImpl(false);
	}
}
