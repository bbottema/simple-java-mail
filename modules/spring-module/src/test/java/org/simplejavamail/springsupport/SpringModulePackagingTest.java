package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigLoader.Property;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringModulePackagingTest {

	private static final String AUTO_CONFIGURATION_IMPORTS =
			"META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
	private static final String AUTO_CONFIGURATION_METADATA = "META-INF/spring-autoconfigure-metadata.properties";
	private static final String CONFIGURATION_METADATA = "META-INF/spring-configuration-metadata.json";

	@Test
	public void productionOutputMustNotContainSpringApplicationConfig() throws URISyntaxException {
		final File productionRoot = productionRoot();

		for (String applicationConfig : Arrays.asList(
				"application.properties",
				"application.yml",
				"application.yaml",
				"bootstrap.properties",
				"bootstrap.yml",
				"bootstrap.yaml")) {
			assertThat(new File(productionRoot, applicationConfig))
					.as("Library production output must not provide %s", applicationConfig)
					.doesNotExist();
		}
	}

	@Test
	void productionOutputContainsOnlyTheImportsBasedAutoConfigurationRegistration() throws IOException, URISyntaxException {
		final File productionRoot = productionRoot();
		final File imports = new File(productionRoot, AUTO_CONFIGURATION_IMPORTS);
		final File metadata = new File(productionRoot, AUTO_CONFIGURATION_METADATA);

		assertThat(imports).isFile();
		assertThat(readUtf8(imports).trim())
				.isEqualTo(SimpleJavaMailAutoConfiguration.class.getName());
		assertThat(new File(productionRoot, "META-INF/spring.factories")).doesNotExist();
		assertThat(metadata).isFile();
		assertThat(readUtf8(metadata)).contains(
				SimpleJavaMailAutoConfiguration.class.getName() + ".ConditionalOnClass=org.simplejavamail.api.SimpleJavaMail");
		assertThat(new File(productionRoot, CONFIGURATION_METADATA)).isFile();
	}

	@Test
	void configurationMetadataCoversEverySupportedProperty() throws IOException, URISyntaxException {
		final String configurationMetadata = readUtf8(new File(productionRoot(), CONFIGURATION_METADATA));

		for (Property property : Property.values()) {
			assertThat(configurationMetadata)
					.as("Spring Boot metadata should describe %s", property.key())
					.contains("\"name\": \"" + metadataNameFor(property) + "\"");
		}
	}

	private static String metadataNameFor(final Property property) {
		switch (property) {
			case JAVAXMAIL_DEBUG_OUTPUT:
				return "simplejavamail.javaxmail.debug-out";
			case CUSTOM_SSLFACTORY_CLASS:
				return "simplejavamail.custom.sslfactory.clazz";
			case DEFAULT_POOL_KEEP_ALIVE_TIME:
				return "simplejavamail.defaults.poolsize-more.keepalivetime";
			default:
				return property.key().replace('_', '-').replace(".*", "");
		}
	}

	private static String readUtf8(final File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static File productionRoot() throws URISyntaxException {
		final URL productionOutput = SimpleJavaMailSpringSupport.class.getProtectionDomain().getCodeSource().getLocation();
		final File productionRoot = new File(productionOutput.toURI());
		assertThat(productionRoot).isDirectory();
		return productionRoot;
	}
}
