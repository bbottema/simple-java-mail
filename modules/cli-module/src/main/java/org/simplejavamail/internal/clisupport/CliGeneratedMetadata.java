package org.simplejavamail.internal.clisupport;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/** The complete pair produced by one explicit build operation. */
@Value
@Builder
class CliGeneratedMetadata {
    @NonNull byte[] options;
    @NonNull byte[] javadoc;
}
