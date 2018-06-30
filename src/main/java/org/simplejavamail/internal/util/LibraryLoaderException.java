package org.simplejavamail.internal.util;

import org.simplejavamail.MailException;

class LibraryLoaderException extends MailException {
	LibraryLoaderException(String message) {
		super(message);
	}
	
	LibraryLoaderException(String message, Throwable cause) {
		super(message, cause);
	}
}
