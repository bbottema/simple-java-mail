package org.simplejavamail.internal.email;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** Per-header explicit composition intent; retained input fields do not become new delivery or structural claims. */
@ApiStatus.Internal
public interface HeaderCompositionBuilder {
    Set<String> getExplicitHeaderNames();
    Set<String> getConfidentialHeaderNames();
    Set<String> getCapturedReceiptGroups();
}
