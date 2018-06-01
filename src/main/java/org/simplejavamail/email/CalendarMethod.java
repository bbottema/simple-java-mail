package org.simplejavamail.email;

/**
 * Possible RFC-2446 VEVENT calendar component methods.
 */
@SuppressWarnings("unused")
public enum CalendarMethod {
	PUBLISH,
	REQUEST,
	REPLY,
	ADD,
	CANCEL,
	REFRESH,
	COUNTER,
	DECLINECOUNTER
}