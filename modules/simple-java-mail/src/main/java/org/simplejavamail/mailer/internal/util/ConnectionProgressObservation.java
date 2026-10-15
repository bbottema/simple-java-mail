package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Transport;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter.ConnectionProgressRegistration;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsRecorder;
import org.simplejavamail.mailer.internal.MailSendProgressScope;

/** Owns only a physical setup's reporting binding. Connection recovery and transport disposal remain separate owners. */
final class ConnectionProgressObservation implements AutoCloseable {
    private final MailSendProgressScope scope;
    @Nullable private final ConnectionProgressRegistration registration;

    private ConnectionProgressObservation(final Transport transport, final MailSendProgressScope scope) {
        this.scope = scope;
        registration = MailTransportLifecycleResolver.observeConnectionSetup(transport, scope);
        if (registration == null) {
            // A broken/ambiguous adapter might have retained its sink before failing to return a registration. Revoke it immediately.
            scope.close();
        }
    }

    @Nullable
    static ConnectionProgressObservation start(final Transport transport, final MailSendDiagnosticsRecorder diagnostics, final MailSendControl control) {
        final MailSendProgressScope scope = diagnostics.openProgressScope(true, control);
        return scope == null ? null : new ConnectionProgressObservation(transport, scope);
    }

    @Override
    public void close() {
        scope.close();
        MailTransportLifecycleResolver.closeProgressRegistration(registration);
    }
}
