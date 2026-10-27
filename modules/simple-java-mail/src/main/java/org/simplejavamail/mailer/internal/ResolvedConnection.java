package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete choices and retained declarations. This value grants no permission to mutate a Session. */
final class ResolvedConnection {
    final CapturedConnectionInputs inputs;
    final Map<String, Object> overrides;
    final ProxyConfig proxy;
    final Map<String, Object> explicitOverrides;
    @Nullable final String shadowedFactoryClassProperty;
    final Map<ConfigurationSetting<Object>, List<ConfigurationDeclaration<Object>>> declarations;

    ResolvedConnection(final CapturedConnectionInputs inputs, final Map<String, Object> overrides, final ProxyConfig proxy,
            final Map<String, Object> explicitOverrides, @Nullable final String shadowedFactoryClassProperty,
            final Map<ConfigurationSetting<Object>, List<ConfigurationDeclaration<Object>>> declarations) {
        this.inputs = inputs;
        this.overrides = CapturedConnectionInputs.immutable(overrides);
        this.proxy = proxy;
        this.explicitOverrides = CapturedConnectionInputs.immutable(explicitOverrides);
        this.shadowedFactoryClassProperty = shadowedFactoryClassProperty;
        final Map<ConfigurationSetting<Object>, List<ConfigurationDeclaration<Object>>> retained = new LinkedHashMap<>();
        declarations.forEach((setting, values) -> retained.put(setting, snapshotDeclarations(setting, values)));
        this.declarations = CapturedConnectionInputs.immutable(retained);
    }

    private static List<ConfigurationDeclaration<Object>> snapshotDeclarations(final ConfigurationSetting<Object> setting,
            final List<ConfigurationDeclaration<Object>> declarations) {
        ConnectionSettings.propertyName(setting);
        for (ConfigurationDeclaration<Object> declaration : declarations) {
            if (!setting.equals(declaration.getSetting())) {
                throw new IllegalArgumentException("Retained connection declarations for logical setting " + setting
                        + " include another logical setting " + declaration.getSetting());
            }
        }
        return List.copyOf(declarations);
    }
}
