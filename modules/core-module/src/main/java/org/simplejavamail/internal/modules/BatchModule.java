package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Gives the Mailer access to the optional batch module's connection pools without depending on its implementation classes.
 * The implementation is loaded through reflection; asynchronous scheduling stays with the Mailer.
 */
public interface BatchModule {

	String NAME = "Advanced batch processing module";

	/**
	 * Initializes the connection pool cluster if not initialized yet.
	 * <p>
	 * Creates connection pool for the cluster key and session combination if it doesn't exist yet.
	 * The registration retains its sending allowance independently of Session properties. The same live pool cannot acquire a different
	 * allowance owner while limits are enabled; callers should share the factory's named group or use a separate Session.
	 */
	void registerToCluster(@NotNull OperationalConfig operationalConfig, @NotNull final UUID clusterKey, @NotNull Session session,
			@NotNull SendingAllowance sendingAllowance);

	/**
	 * @param stickySession Indicates whether transport should be from this specific Session, or any session instance from the cluster.
	 *                      Useful when testing connections.
	 *
	 * @return A (new) {@link Transport} for the given session from the SMTP connection pool.
	 */
	@NotNull
	LifecycleDelegatingTransport acquireTransport(@NotNull UUID clusterKey, @NotNull Session session, boolean stickySession, @Nullable MailSendControl control);

	/**
	 * Selects a registered destination without borrowing a connection or opening a socket. The selected Session determines
	 * destination-specific sending allowance; waiting for it must finish before {@link SelectedPoolTransport#acquireTransport(MailSendControl)}.
	 * Selection and acquisition have separate pool timeouts, both bounded by the same overall send control.
	 */
	@NotNull
	SelectedPoolTransport selectTransport(@NotNull UUID clusterKey, @NotNull Session session, boolean stickySession, @Nullable MailSendControl control);

	/**
	 * Shuts down connection pool(s) and closes remaining open connections.
	 * Waits until all connections still in use become available again to deallocate them as well.
	 */
	@NotNull
	Future<Void> shutdownConnectionPools(@NotNull Session session);
}
