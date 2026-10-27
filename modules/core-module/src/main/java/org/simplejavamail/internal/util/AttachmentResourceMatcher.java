package org.simplejavamail.internal.util;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.AttachmentResource;

import java.util.Objects;

/** Conservative template matching; deliberately independent of public resource equality. */
@ApiStatus.Internal
public final class AttachmentResourceMatcher {

    private AttachmentResourceMatcher() {
    }

    public static boolean sameResource(final AttachmentResource first, final AttachmentResource second) {
        return first == second || sameMetadata(first, second)
                && AttachmentDataSources.sameSource(first.getDataSource(), second.getDataSource());
    }

    private static boolean sameMetadata(final AttachmentResource first, final AttachmentResource second) {
        return Objects.equals(first.getName(), second.getName())
                && Objects.equals(first.getDescription(), second.getDescription())
                && Objects.equals(first.getContentId(), second.getContentId())
                && first.getContentTransferEncoding() == second.getContentTransferEncoding()
                && first.getPreEncodedContentTransferEncoding() == second.getPreEncodedContentTransferEncoding();
    }
}
