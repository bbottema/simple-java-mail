package org.simplejavamail.batch;

/**
 * Determines how a registered Session is selected when an operation targets a cluster rather than one exact Session.
 */
public enum BatchLoadBalancingStrategy {
	/**
	 * Select registered Session pools in a fixed, repeating sequence.
	 */
	ROUND_ROBIN,

	/**
	 * Select a registered Session pool randomly for each claim.
	 */
	RANDOM_ACCESS
}
