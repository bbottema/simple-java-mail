package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Retains the selected declaration and all candidates so feature validation cannot lose competing inputs. */
@ApiStatus.Internal
public final class ConfigurationResolution<T> {
    private final ConfigurationSetting<T> setting;
    private final List<ConfigurationDeclaration<T>> declarations;
    @Nullable private final ConfigurationDeclaration<T> selected;

    ConfigurationResolution(final ConfigurationSetting<T> setting, final List<ConfigurationDeclaration<T>> declarations,
            @Nullable final ConfigurationDeclaration<T> selected) {
        this.setting = setting;
        this.declarations = List.copyOf(declarations);
        this.selected = selected;
    }

    public ConfigurationSetting<T> getSetting() { return setting; }
    public List<ConfigurationDeclaration<T>> getDeclarations() { return declarations; }
    @Nullable public ConfigurationDeclaration<T> getSelected() { return selected; }
    public ConfigurationChoice<T> getChoice() { return selected == null ? ConfigurationChoice.inherit() : selected.getChoice(); }
    @Nullable public T valueOrNull() { return getChoice().valueOrNull(); }
    @Override public String toString() { return selected == null ? "Inherited configuration" : selected.toString(); }
}
