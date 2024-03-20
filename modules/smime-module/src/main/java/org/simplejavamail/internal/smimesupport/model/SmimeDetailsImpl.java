package org.simplejavamail.internal.smimesupport.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;

@Getter
@RequiredArgsConstructor
public class SmimeDetailsImpl implements SmimeDetails {
    @NotNull private final String smimeMime;
    @Nullable private final String signedBy;
}
