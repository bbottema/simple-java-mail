package org.simplejavamail.internal.smimesupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.Signature;

import static org.assertj.core.api.Assertions.assertThat;

class SmimeAlgorithmDocumentationTest {
    @ParameterizedTest
    @ValueSource(strings = {"SHA256withRSA", "SHA384withRSA", "SHA512withRSA",
            "SHA256withRSAandMGF1", "SHA384withRSAandMGF1", "SHA512withRSAandMGF1",
            "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "SHA256withDSA", "SHA384withDSA", "SHA512withDSA",
            "SHA3-256withRSA", "SHA3-384withRSA", "SHA3-512withRSA", "SHA3-256withECDSA", "SHA3-384withECDSA", "SHA3-512withECDSA",
            "SHA256withSM2", "SM3withSM2"})
    void documentedSignatureNamesResolveInTheModulesProvider(final String algorithm) throws Exception {
        assertThat(new DefaultSignatureAlgorithmIdentifierFinder().find(algorithm)).isNotNull();
        assertThat(Signature.getInstance(algorithm, new BouncyCastleProvider())).isNotNull();
    }
}
