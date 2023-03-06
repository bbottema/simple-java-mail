package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;

/**
 * @see EmailPopulatingBuilderFactory
 */
public final class EmailPopulatingBuilderFactoryImpl implements EmailPopulatingBuilderFactory {
	@Override
	public EmailPopulatingBuilder create() {
		// FIXME shouldn't this be .ignoringDefaults(true)?
		return 	new EmailPopulatingBuilderImpl().ignoringOverrides(true);
	}
}