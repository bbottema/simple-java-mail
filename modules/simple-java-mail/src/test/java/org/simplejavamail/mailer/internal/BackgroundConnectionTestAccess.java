package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.Mailer;

/** Test-only access to the recovery state observed by background pool allocation. */
public final class BackgroundConnectionTestAccess {
    private BackgroundConnectionTestAccess() {
    }

    public static boolean isAllowed(final Mailer mailer) {
        return ((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed();
    }
}
