package org.simplejavamail.springbootstarter;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleJavaMailSpringBootStarterPackagingTest {

	private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";

	@Test
	void starterHasAStableModuleNameWithoutAddingProductionClasses() throws Exception {
		final File productionRoot = productionRoot();

		try (Stream<Path> productionFiles = Files.walk(productionRoot.toPath())) {
			final List<Path> productionClasses = productionFiles
					.filter(path -> path.getFileName().toString().endsWith(".class"))
					.collect(Collectors.toList());
			assertThat(productionClasses).isEmpty();
		}

		try (FileInputStream manifestInput = new FileInputStream(new File(productionRoot, "META-INF/MANIFEST.MF"))) {
			assertThat(new Manifest(manifestInput).getMainAttributes().getValue("Automatic-Module-Name"))
					.isEqualTo("org.simplejavamail.spring.boot.starter");
		}
	}

	@Test
	void bootRuntimeDependenciesRemainApplicationProvided() throws Exception {
		final File starterModule = moduleRoot();
		final Document starterPom = readPomDocument(new File(starterModule, "pom.xml"));
		final Document springModulePom = readPomDocument(new File(starterModule.getParentFile(), "spring-module/pom.xml"));

		assertThat(directDependencyScope(starterPom, SPRING_BOOT_GROUP_ID, "spring-boot-starter"))
				.as("The consuming application should supply Spring Boot")
				.isEqualTo("provided");
		assertThat(directDependencyScope(starterPom, "org.slf4j", "slf4j-api"))
				.as("The consuming Boot line should supply its matching SLF4J generation")
				.isEqualTo("provided");
		assertThat(directDependencyScope(springModulePom, SPRING_BOOT_GROUP_ID, "spring-boot-autoconfigure"))
				.as("The consuming application should supply Spring Boot auto-configuration")
				.isEqualTo("provided");
	}

	private static Document readPomDocument(final File pomFile) throws Exception {
		final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		return documentBuilderFactory.newDocumentBuilder().parse(pomFile);
	}

	private static String directDependencyScope(final Document pomDocument, final String groupId, final String artifactId) throws Exception {
		final String directDependencies = "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']";
		final String matchingCoordinates = String.format(
				"[*[local-name()='groupId']='%s'][*[local-name()='artifactId']='%s']",
				groupId,
				artifactId);
		return XPathFactory.newInstance()
				.newXPath()
				.evaluate(directDependencies + matchingCoordinates + "/*[local-name()='scope']/text()", pomDocument)
				.trim();
	}

	private static File productionRoot() throws URISyntaxException {
		final File testOutput = new File(SimpleJavaMailSpringBootStarterPackagingTest.class
				.getProtectionDomain()
				.getCodeSource()
				.getLocation()
				.toURI());
		final File productionRoot = new File(testOutput.getParentFile(), "classes");
		assertThat(productionRoot).isDirectory();
		return productionRoot;
	}

	private static File moduleRoot() throws URISyntaxException {
		return productionRoot().getParentFile().getParentFile();
	}
}
