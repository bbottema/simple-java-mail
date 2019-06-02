package org.simplejavamail.api.internal.batchsupport;

import javax.mail.Transport;

import javax.annotation.Nonnull;

/**
 * Transport life cycle management is done by the batch module, so this class is used to signal back when the Transport
 * resource has had its use for the current send-email / test-connection invocation.
 */
public interface LifecycleDelegatingTransport {
	@Nonnull Transport getTransport();
	void signalTransportUsed();
}
