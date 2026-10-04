package org.simplejavamail.springbootsupport;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Map;

/**
 * Reuses the ordinary property model to generate IDE hints for the locked namespace. Runtime loading still uses Spring's Environment.
 *
 * @deprecated Metadata only, like {@link SimpleJavaMailProperties}; do not bind or instantiate this class in application code.
 */
@Deprecated
@ConfigurationProperties(prefix = "simplejavamail.locked")
@Getter
@Setter
public class LockedSimpleJavaMailProperties {

    // The processor does not expand inherited groups. Reuse their types explicitly; leaf definitions stay in one place.
    @NestedConfigurationProperty private SimpleJavaMailProperties.Javaxmail javaxmail;
    private String transportstrategy;
    private Map<String, String> extraproperties;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Smtp smtp;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Proxy proxy;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Defaults defaults;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Smime smime;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Dkim dkim;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Embeddedimages embeddedimages;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Disable disable;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Custom custom;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Transport transport;
    @NestedConfigurationProperty private SimpleJavaMailProperties.Opportunistic opportunistic;
}
