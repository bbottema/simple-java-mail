/**
 * Supported standalone Jakarta Mail batch orchestration API.
 * <p>
 * Use {@link org.simplejavamail.batch.BatchTransportExecutor} when an application creates its own
 * {@link jakarta.mail.Session} and messages but wants callback-scoped access to pooled Transports, clustered Session
 * selection, asynchronous submission, automatic release/invalidation, and deterministic shutdown. Applications that
 * need to control raw leases should use {@code smtp-connection-pool} directly instead.
 * <p>
 * Exactly one component may own the physical connection pool. Sessions registered here must not select the Jakarta
 * Mail {@code smtppool} provider.
 */
package org.simplejavamail.batch;
