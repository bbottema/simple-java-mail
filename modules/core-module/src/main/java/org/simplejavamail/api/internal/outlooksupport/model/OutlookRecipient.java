package org.simplejavamail.api.internal.outlooksupport.model;

import java.util.Set;

/**
 * @see OutlookMessage
 */
public interface OutlookRecipient {
	Set<Integer> getPropertyCodes();
	String getAddress();
	String getName();
}