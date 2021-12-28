package org.simplejavamail.api.mailer.config;

/**
 * Defines the various types of load balancing modes supported by the connection pool ion the <a href="http://http://www.simplejavamail.org/configuration.html#section-batch-and-clustering">batch-module</a>.
 * <p>
 * This is only relevant if you have multiple mail servers in one or more clusters. Currently it is impossible to define different load balancing strategies for different clusters.
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