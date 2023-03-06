package org.simplejavamail.api.email;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
@Getter
// FIXME temporary workaround to make sure we update all the calls that require a final Email, with a fcall to EmailGovernance.produceFinalEmail() first
@SuppressFBWarnings("EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WI")
public class EmailWithDefaultsAndOverridesApplied {
    @Delegate
    @NotNull Email delegate;

    public int hashCode() {
        return 0;
    }
}