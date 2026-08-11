package org.simplejavamail.batch;

import jakarta.mail.Session;
import jakarta.mail.Transport;

/**
 * Work performed with one claimed SMTP transport.
 * <p>
 * The Session is the one actually selected by the cluster, which may differ between invocations. The Session and
 * Transport are valid only for the duration of {@link #execute(Session, Transport)}. Callers must not connect or close
 * the Transport: returning normally releases it for reuse, while an escaping exception or error invalidates it.
 *
 * @param <T> result type
 * @param <E> checked failure type produced by the operation
 */
@FunctionalInterface
public interface BatchTransportOperation<T, E extends Exception> {

	/**
	 * Performs work using the selected Session and its connected Transport.
	 *
	 * @param session the Session actually selected for this invocation
	 * @param transport the connected Transport claimed for this invocation
	 * @return the operation result
	 * @throws E when the operation cannot complete; the transport will be invalidated
	 */
	T execute(Session session, Transport transport) throws E;
}
