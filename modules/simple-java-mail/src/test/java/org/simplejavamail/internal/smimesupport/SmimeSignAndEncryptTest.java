package org.simplejavamail.internal.smimesupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.CertificationUtil;
import testutil.ConfigLoaderTestHelper;

import java.io.File;
import java.security.Security;
import java.security.cert.X509Certificate;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.CertificationUtil.extractSignedBy;

public class SmimeSignAndEncryptTest {

    private static final String RESOURCES_PATH = determineResourceFolder("simple-java-mail") + "/test/resources";
    private static final String RESOURCES_PKCS = RESOURCES_PATH + "/pkcs12";

    private EmailPopulatingBuilder builder;

    @Before
    public void setup() {
        ConfigLoaderTestHelper.clearConfigProperties();
        builder = EmailBuilder.startingBlank();
    }

    @Test
    public void testSignWithSmime_WithConfigObject() {
        builder.signWithSmime(SmimeSigningConfig.builder()
                .pkcs12Config(loadPkcs12KeyStore())
                .build());

        final Email email = builder.buildEmail();

        assertThat(email.getSmimeSigningConfig()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getStorePassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyAlias()).isEqualTo("smime_test_user_alias_rsa");
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyPassword()).isEqualTo("letmein".toCharArray());
    }

    @Test
    public void testSignWithSmime_WithConfigParameters() {
        builder.signWithSmime(SmimeSigningConfig.builder()
                .pkcs12Config(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_rsa", "letmein")
                .build());

        final Email email = builder.buildEmail();

        assertThat(email.getSmimeSigningConfig()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getStorePassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyAlias()).isEqualTo("smime_test_user_alias_rsa");
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyPassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getSmimeSigningConfig().getSignatureAlgorithm()).isNull();
    }

    @Test
    public void testSignWithSmime_WithConfigObject_AlternativeSignatureAlgorithm() {
        builder.signWithSmime(SmimeSigningConfig.builder()
                .pkcs12Config(loadPkcs12KeyStore())
                .signatureAlgorithm("SHA384withDSA")
                .build());

        final Email email = builder.buildEmail();

        assertThat(email.getSmimeSigningConfig()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData()).isNotNull();
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getStorePassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyAlias()).isEqualTo("smime_test_user_alias_rsa");
        assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyPassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getSmimeSigningConfig().getSignatureAlgorithm()).isEqualTo("SHA384withDSA");
    }

    @Test
    public void testEncryptWithSmime_FromFile() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        builder.encryptWithSmime(SmimeEncryptionConfig.builder()
                .x509Certificate(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"))
                .build());

        final SmimeEncryptionConfig smimeEncryptionConfig = builder.buildEmail().getSmimeEncryptionConfig();
        assertThat(smimeEncryptionConfig).isNotNull();

        final X509Certificate certificateOut = smimeEncryptionConfig.getX509Certificate();
        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    @Test
    public void testEncryptWithSmime_FromFilePath() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        builder.encryptWithSmime(SmimeEncryptionConfig.builder()
                .x509Certificate(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt")
                .build());

        final SmimeEncryptionConfig smimeEncryptionConfig = builder.buildEmail().getSmimeEncryptionConfig();
        assertThat(smimeEncryptionConfig).isNotNull();

        final X509Certificate certificateOut = smimeEncryptionConfig.getX509Certificate();
        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    @Test
    public void testEncryptWithSmime_FromCertificate() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        X509Certificate certificateIn = CertificationUtil.readFromPem(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

        builder.encryptWithSmime(SmimeEncryptionConfig.builder()
                .x509Certificate(certificateIn)
                .build());

        final SmimeEncryptionConfig smimeEncryptionConfig = builder.buildEmail().getSmimeEncryptionConfig();
        assertThat(smimeEncryptionConfig).isNotNull();

        final X509Certificate certificateOut = smimeEncryptionConfig.getX509Certificate();
        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    private static void assertSignedBy(X509Certificate certificate, @SuppressWarnings("SameParameterValue") final String expectedSignedBy)
            throws OperatorCreationException {
        assertThat(extractSignedBy(certificate)).isEqualTo(expectedSignedBy);
    }
}