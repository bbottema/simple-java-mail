package org.simplejavamail.internal.batchsupport;

class BatchException extends RuntimeException {

	static final String ERROR_ACQUIRING_KEYED_POOLABLE = "Was unable to obtain a poolable object for key:\t%n%s";

	BatchException(final String msg, final Throwable cause) {
		super(msg, cause);
	}
}