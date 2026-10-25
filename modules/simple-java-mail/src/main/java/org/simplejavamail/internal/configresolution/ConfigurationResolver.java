package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/** Pure selection with feature-owned precedence. Interpretation and requirements remain at the feature boundary. */
@ApiStatus.Internal
public final class ConfigurationResolver {
    private ConfigurationResolver() { }

    public static <T> ConfigurationResolution<T> resolve(final List<ConfigurationDeclaration<T>> declarations,
            final List<ConfigurationOrigin.Kind> precedence) {
        ConfigurationDeclaration<T> selected = null;
        int selectedRank = Integer.MAX_VALUE;
        for (ConfigurationDeclaration<T> declaration : declarations) {
            if (!declaration.isEligible() || declaration.getChoice().getState() == ConfigurationChoice.State.INHERIT) {
                continue;
            }
            final int rank = precedence.indexOf(declaration.getOrigin().getKind());
            if (rank < 0) {
                throw new IllegalArgumentException("No precedence for configuration source " + declaration.getOrigin().getKind());
            }
            if (rank < selectedRank) {
                selected = declaration;
                selectedRank = rank;
            }
        }
        return new ConfigurationResolution<>(declarations, selected);
    }
}
