package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringModulePackagingTest {

	@Test
	public void productionOutputMustNotContainSpringApplicationConfig() throws URISyntaxException {
		final URL productionOutput = SimpleJavaMailSpringSupport.class.getProtectionDomain().getCodeSource().getLocation();
		final File productionRoot = new File(productionOutput.toURI());

		assertThat(productionRoot).isDirectory();
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
}
