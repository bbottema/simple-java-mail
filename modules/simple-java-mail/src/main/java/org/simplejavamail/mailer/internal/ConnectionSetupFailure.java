package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.internal.util.SmtpServiceUnavailableException;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Marks a failed usable-connection acquisition while retaining the public MailException contract.
 * Submission and capacity failures must never use this routing evidence.
 */
public final class ConnectionSetupFailure extends MailException {

	private static final long serialVersionUID = 1L;
	private final Kind connectionKind;

	public ConnectionSetupFailure(final Throwable cause) {
		super(MailFailureKind.CONNECTION,
				"Couldn't obtain a usable SMTP connection; no message was submitted to this destination.", cause, null);
		connectionKind = classify(cause);
	}

	boolean allowsFailover(final FailoverPolicy policy) {
		if (hasUnresolvedCleanup()) {
			return false;
		}
		switch (connectionKind) {
			case AVAILABILITY:
				return true;
			case AUTHENTICATION:
				return policy.isFailoverOnAuthenticationFailure();
			case CERTIFICATE:
				return policy.isFailoverOnCertificateVerificationFailure();
			default:
				return false;
		}
	}

	/** Acquisition wrappers retain disposal failures as suppressed exceptions. Starting another destination must wait for completed cleanup. */
	boolean hasUnresolvedCleanup() {
		return causalFailures(this).stream().anyMatch(failure -> failure.getSuppressed().length != 0);
	}

	String explanation() {
		switch (connectionKind) {
			case AVAILABILITY: return "Connection unavailable";
			case AUTHENTICATION: return "The server rejected authentication";
			case CERTIFICATE: return "Certificate or server identity verification failed";
			default: return "Connection setup failed without reliable failover evidence";
		}
	}

	/** Security failures outrank nested I/O symptoms. Jakarta Mail may retain evidence in nextException instead of cause. */
	private static Kind classify(final Throwable failure) {
		Kind result = Kind.UNKNOWN;
		for (Throwable current : causalFailures(failure)) {
			if (current instanceof CertificateException || current instanceof SSLPeerUnverifiedException) {
				return Kind.CERTIFICATE;
			}
			if (current instanceof AuthenticationFailedException) {
				result = Kind.AUTHENTICATION;
			} else if (result != Kind.AUTHENTICATION && isAvailabilityFailure(current)) {
				result = Kind.AVAILABILITY;
			}
		}
		return result;
	}

	private static Set<Throwable> causalFailures(final Throwable failure) {
		final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		final ArrayDeque<Throwable> pending = new ArrayDeque<>();
		pending.add(failure);
		while (!pending.isEmpty()) {
			final Throwable current = pending.removeFirst();
			if (!visited.add(current)) {
				continue;
			}
			if (current.getCause() != null) {
				pending.add(current.getCause());
			}
			if (current instanceof MessagingException && ((MessagingException) current).getNextException() != null) {
				pending.add(((MessagingException) current).getNextException());
			}
		}
		return visited;
	}

	private static boolean isAvailabilityFailure(final Throwable failure) {
		return failure instanceof UnknownHostException || failure instanceof ConnectException || failure instanceof NoRouteToHostException
				|| failure instanceof SocketTimeoutException || failure instanceof EOFException || failure instanceof SocketException
				|| failure instanceof SmtpServiceUnavailableException;
	}

	private enum Kind {
		AVAILABILITY,
		AUTHENTICATION,
		CERTIFICATE,
		UNKNOWN
	}
}
