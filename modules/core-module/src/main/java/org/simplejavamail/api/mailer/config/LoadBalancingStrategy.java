package org.simplejavamail.api.mailer.config;

/**
 * Defines the various types of load balancing modes supported by the connection pool ion the <a href="https://www.simplejavamail.org/configuration.html#section-batch-and-clustering">batch-module</a>.
 * <p>
 * This is only relevant if you have multiple mail servers in one or more clusters. When using the Java API, the first
 * {@link org.simplejavamail.api.mailer.Mailer} registered for a cluster determines the load balancing strategy for that cluster.
 */
@SuppressWarnings("unused")
public enum LoadBalancingStrategy {
	/**
	 * Fixed sequence, looping through all available connection pools in a cluster.
	 */
	ROUND_ROBIN,
	/**
	 * Randomly choose a connection pool (server) to request a connection ({@link jakarta.mail.Transport}) object from.
	 */
	RANDOM_ACCESS;

	public static final String ROUND_ROBIN_REF = "ROUND_ROBIN";
	public static final String RANDOM_ACCESS_REF = "RANDOM_ACCESS";
}
