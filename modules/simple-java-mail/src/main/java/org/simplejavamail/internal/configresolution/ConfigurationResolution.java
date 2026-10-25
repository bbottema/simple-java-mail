package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Retains the selected declaration and all candidates so feature validation cannot lose competing inputs. */
@ApiStatus.Internal
public final class ConfigurationResolution<T> {
    private final List<ConfigurationDeclaration<T>> declarations;
    @Nullable private final ConfigurationDeclaration<T> selected;

    ConfigurationResolution(final List<ConfigurationDeclaration<T>> declarations, @Nullable final ConfigurationDeclaration<T> selected) {
        this.declarations = List.copyOf(declarations);
        this.selected = selected;
    }

    public List<ConfigurationDeclaration<T>> getDeclarations() { return declarations; }
    @Nullable public ConfigurationDeclaration<T> getSelected() { return selected; }
    public ConfigurationChoice<T> getChoice() { return selected == null ? ConfigurationChoice.inherit() : selected.getChoice(); }
    @Nullable public T valueOrNull() { return getChoice().valueOrNull(); }
    @Override public String toString() { return selected == null ? "Inherited configuration" : selected.toString(); }
}
