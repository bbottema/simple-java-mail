package org.simplejavamail.api.internal.smimesupport.model;

/**
 * Implemented by the S/MIME module to return some basic metadata to the main Simple Javan Mail module.
 */
public interface SmimeDetails {
	String getSmimeMime();
	String getSignedBy();
}
