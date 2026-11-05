package org.simplejavamail.internal.smimesupport;

/** Known CMS interpretation failure after the attachment's selected bytes have been acquired. */
@SuppressWarnings("serial")
final class MalformedSignedContentException extends SmimeException {

    MalformedSignedContentException(final Exception cause) {
        super("The independent S/MIME attachment's signed content could not be interpreted", cause);
    }
}
