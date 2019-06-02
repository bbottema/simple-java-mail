package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import org.simplejavamail.MailException;

import static java.lang.String.format;

class AcquireKeyedPoolableException extends MailException {

	private static final String ERROR_ACQUIRING_KEYED_POOLABLE = "Was unable to obtain a poolable object for key:\t\n%s";

	AcquireKeyedPoolableException(final Object context, final Throwable cause) {
		super(format(ERROR_ACQUIRING_KEYED_POOLABLE, context), cause);
	}
}
