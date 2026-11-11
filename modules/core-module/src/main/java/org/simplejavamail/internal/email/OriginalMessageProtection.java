package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;

/** Captured relationship between retained original bytes and incoming confidentiality, used only by library reuse helpers. */
@ApiStatus.Internal
public enum OriginalMessageProtection {
    /** The retained payload's relationship to recovered confidential material was not captured. */
    UNKNOWN,
    /** Complete supplied input was retained before this conversion opened its own protection. */
    AS_RECEIVED,
    /** Extracted input contains ancestor-confidential material outside any encryption retained by this resource. */
    CONFIDENTIAL,
    /** Actual intact encryption still covers the retained payload; signing alone never establishes this state. */
    INTACT_PROTECTED
}
