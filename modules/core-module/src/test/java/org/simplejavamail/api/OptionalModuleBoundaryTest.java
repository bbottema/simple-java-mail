package org.simplejavamail.api;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalModuleBoundaryTest {
    @Test
    void configurationCanBeCapturedWithoutOptionalImplementations() {
        assertThat(getClass().getClassLoader().getResource("org/simplejavamail/internal/batchsupport/BatchSupport.class")).isNull();
        assertThat(getClass().getClassLoader().getResource("org/simplejavamail/internal/dkimsupport/DKIMSigner.class")).isNull();

        final ConnectionPoolClusterConfig pool = ConnectionPoolClusterConfig.builder().coreSize(-1).maxSize(0).expireAfterCreation(Duration.ofMillis(0)).build();
        assertThat(pool.getCoreSize()).isEqualTo(-1);
        assertThat(pool.getMaxSize()).isZero();
        assertThat(pool.getExpireAfterCreation()).isEqualTo(Duration.ZERO);

        final DkimConfig signing = DkimConfig.builder().dkimPrivateKeyData("not yet parsed")
                .excludedHeadersFromDkimDefaultSigningList("From").build();
        assertThat(signing.getExcludedHeadersFromDkimDefaultSigningList()).containsExactly("From");
    }

    @Test
    void coreDoesNotImportOptionalFeatureImplementationsOrTheirLibraries() throws Exception {
        final List<String> forbiddenImports = List.of("org.simplejavamail.internal.batchsupport", "org.simplejavamail.internal.dkimsupport",
                "org.simplejavamail.internal.smimesupport", "org.simplejavamail.internal.openpgpsupport", "org.simplejavamail.internal.outlooksupport",
                "org.simplejavamail.internal.authenticatedsockssupport", "org.simplejavamail.internal.otelsupport", "org.simplejavamail.smtpconnectionpool",
                "org.bbottema.genericobjectpool", "org.bbottema.clusteredobjectpool", "org.bouncycastle", "org.eclipse.angus", "io.opentelemetry");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList())) {
                for (String line : Files.readAllLines(source)) {
                    if (line.stripLeading().startsWith("import ")) {
                        assertThat(line).as(source.toString()).doesNotContain(forbiddenImports.toArray(new String[0]));
                    }
                }
            }
        }
        assertThat(Files.readString(Path.of("pom.xml"))).doesNotContain("org.bouncycastle");
    }
}
