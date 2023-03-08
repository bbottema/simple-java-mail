package org.simplejavamail.api.internal.general;

import org.simplejavamail.api.email.EmailPopulatingBuilder;

/**
 * This factory allows modules to create new builders. This use case is pretty rare as modules get the current builder along already,
 * but in case of nested Outlook emails for example, we need a new clean builder to construct a new Email object with.
 */
public interface EmailPopulatingBuilderFactory {
	EmailPopulatingBuilder create();
}