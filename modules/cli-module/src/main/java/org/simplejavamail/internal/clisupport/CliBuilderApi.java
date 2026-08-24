package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

/** The builder roots that define the generated CLI surface and its cache fingerprint. */
public final class CliBuilderApi {
	private static final Class<?>[] ROOTS = {
			EmailStartingBuilder.class,
			CliEmailRecipientBuilder.class,
			MailerRegularBuilder.class,
			MailerFromSessionBuilder.class
	};

	private CliBuilderApi() {
	}

	public static Class<?>[] roots() {
		return ROOTS.clone();
	}
}
