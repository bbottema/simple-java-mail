package org.simplejavamail.api.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;

/**
 * Transport life cycle management is done by the batch module, so this class is used to signal back when the Transport
 * resource has had its use for the current send-email / test-connection invocation.
 */
public interface LifecycleDelegatingTransport {
	@NotNull Session getSessionUsedToObtainTransport();
	@NotNull Transport getTransport();
	void signalTransportUsed();
	void signalTransportFailed();
}
