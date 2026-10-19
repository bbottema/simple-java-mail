package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemFilePathToX509CertificateFunctionTest {
    @TempDir Path directory;

    @Test
    void readsAnX509CertificateThroughTheJdkFactory() {
        final Path certificate = Path.of("../simple-java-mail/src/test/resources/pkcs12/smime_test_user.pem.standard.crt");
        assertThat(new PemFilePathToX509CertificateFunction().convertFile(certificate.toFile()).getType()).isEqualTo("X.509");
    }

    @Test
    void preservesTheCertificateErrorForMalformedInput() throws Exception {
        final Path invalid = Files.writeString(directory.resolve("invalid.pem"), "not a certificate");
        assertThatThrownBy(() -> new PemFilePathToX509CertificateFunction().convertFile(invalid.toFile()))
                .isInstanceOf(IncompatibleTypeException.class).hasCauseInstanceOf(CertificateException.class);
    }
}
