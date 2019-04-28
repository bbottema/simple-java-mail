package org.simplejavamail.mailer.internal.mailsender.concurrent;

public abstract class NamedRunnable implements Runnable {

	private final String name;

	protected NamedRunnable(final String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return name;
	}
}