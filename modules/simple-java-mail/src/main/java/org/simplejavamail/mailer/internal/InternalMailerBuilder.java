package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailerGenericBuilder;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@SuppressWarnings("UnusedReturnValue")
public interface InternalMailerBuilder<T extends MailerGenericBuilder<?>> extends MailerGenericBuilder<T> {
	boolean isExecutorServiceUserProvided();
}