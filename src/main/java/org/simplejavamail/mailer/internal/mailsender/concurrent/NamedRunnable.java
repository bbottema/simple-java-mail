package org.simplejavamail.mailer.internal.mailsender.concurrent;

public abstract class NamedRunnable implements Runnable {
	
	private final String name;
	
	protected NamedRunnable(String name) {
		this.name = name;
	}
	
	@Override
	public String toString() {
		return name;
	}
}
