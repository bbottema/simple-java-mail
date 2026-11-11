package org.simplejavamail.api.email;

/** Known native-body derivation; UNKNOWN does not imply that the source was plain text. */
public enum ValueDerivation {
    NONE, NATIVE_HTML, RTF_ENCAPSULATED_HTML, RTF_ENCAPSULATED_TEXT, RTF_RENDERED_HTML, UNKNOWN
}
