package org.simplejavamail.api.email;

/**
 * Since defaults and overrides are not applied all the way in the beginning anymore when creating an Email instance, at the time of sending we want to
 * double-check that defaults and overrides are applied by that time at least. Making this a compile-time check proved to be very cumbersome and confusing
 * to the user, so finally we opted for a runtime check behind the scenes.
 */
public interface EmailWithDefaultsAndOverridesApplied {
    void markAsDefaultsAndOverridesApplied();
    void verifyDefaultsAndOverridesApplied();
}