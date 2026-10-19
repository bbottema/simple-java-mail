package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.List;
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
	 * The module resolves the selected cluster's overrides and validates its effective settings before creating pool resources.
	 * Core configuration holders do not apply pooling rules, and the caller does not invoke this boundary when pooling is unavailable.
	 * The registration retains its sending allowance independently of Session properties. The same live pool cannot acquire a different
	 * allowance owner while limits are enabled; callers should share the factory's named group or use a separate Session.
	 */
	void registerToCluster(@NotNull OperationalConfig operationalConfig, @NotNull final UUID clusterKey, @NotNull Session session,
			@NotNull SendingAllowance sendingAllowance);

	/**
	 * Registers a pool with factory-owned connection observation. The observer records physical setup outcomes and can defer only
	 * background replenishment while a failover route waits to recheck a failed destination; it does not select destinations or retry sends.
	 * Uses the same configuration validation as {@link #registerToCluster(OperationalConfig, UUID, Session, SendingAllowance)}.
	 */
	void registerToCluster(@NotNull OperationalConfig operationalConfig, @NotNull UUID clusterKey, @NotNull Session session,
			@NotNull SendingAllowance sendingAllowance, @NotNull PoolConnectionObserver observer);

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
	 * Returns finite, pinned registrations in the pool's configured selection order. Selection borrows no connections, so callers can
	 * decide which destination's allowance to reserve before acquiring that destination's connection.
	 */
	@NotNull List<SelectedPoolTransport> selectTransportCandidates(@NotNull UUID clusterKey, @Nullable MailSendControl control);

	/**
	 * Shuts down connection pool(s) and closes remaining open connections.
	 * Waits until all connections still in use become available again to deallocate them as well.
	 */
	@NotNull
	Future<Void> shutdownConnectionPools(@NotNull Session session);
}
