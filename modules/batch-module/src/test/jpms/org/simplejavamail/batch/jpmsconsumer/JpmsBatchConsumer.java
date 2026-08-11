package org.simplejavamail.batch.jpmsconsumer;

import jakarta.mail.Session;
import org.simplejavamail.batch.BatchTransportExecutor;

import java.util.Properties;

public final class JpmsBatchConsumer {

	private JpmsBatchConsumer() {
	}

	public static BatchTransportExecutor<String> newExecutor() {
		final BatchTransportExecutor<String> executor = BatchTransportExecutor.<String>builder().build();
		executor.registerSession("outbound", Session.getInstance(new Properties()));
		return executor;
	}
}
