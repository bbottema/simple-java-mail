package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailFailureKind;

import java.util.Properties;

/** Pins MIME preparation metadata independently of the sending Session's owner and resource lifetime. */
final class RehearsalSessionContext {
    private final Session sendingSession;
    private final String ownerProperty;
    private boolean frozen;
    @Nullable private Properties frozenProperties;
    @Nullable private Throwable captureFailure;

    RehearsalSessionContext(final Session sendingSession, final String ownerProperty) {
        this.sendingSession = sendingSession;
        this.ownerProperty = ownerProperty;
    }

    Session capture() {
        final Properties properties;
        try {
            synchronized (this) {
                rethrowCaptureFailure();
                properties = frozen ? SessionPropertyObservation.snapshot(frozenProperties) : captureProperties();
            }
            return quietMimeSession(properties);
        } catch (RuntimeException failure) {
            throw new MailerException(MailFailureKind.PREPARATION,
                    "Unable to capture this Mailer's MIME preparation configuration", failure);
        }
    }

    /** Freeze only configuration metadata; no content, security processing or sending resources are involved. */
    synchronized void freezeForShutdown() {
        if (frozen) { return; }
        frozen = true;
        try {
            frozenProperties = captureProperties();
        } catch (RuntimeException failure) {
            captureFailure = failure;
        } catch (Error failure) {
            captureFailure = failure;
            throw failure;
        }
    }

    private Properties captureProperties() {
        final Properties properties = SessionPropertyObservation.snapshot(sendingSession.getProperties());
        properties.remove(ownerProperty);
        SessionBasedEmailToMimeMessageConverter.removeSendingContext(properties);
        return properties;
    }

    private void rethrowCaptureFailure() {
        if (captureFailure instanceof Error) { throw (Error) captureFailure; }
        if (captureFailure != null) { throw (RuntimeException) captureFailure; }
    }

    /** Session bootstrap logging is not part of rehearsal; retain the property view without enabling its debug output. */
    private static Session quietMimeSession(final Properties properties) {
        final boolean suppliedDebug = properties.containsKey("mail.debug");
        final Object debug = properties.get("mail.debug");
        properties.setProperty("mail.debug", "false");
        try {
            return Session.getInstance(properties);
        } finally {
            if (suppliedDebug) { properties.put("mail.debug", debug); }
            else { properties.remove("mail.debug"); }
        }
    }
}
