package org.simplejavamail.internal.smimesupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
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
        builder.signWithSmime(loadPkcs12KeyStore());

        final Email email = builder.buildEmail();

        assertThat(email.getPkcs12ConfigForSmimeSigning()).isNotNull();
        assertThat(email.getPkcs12ConfigForSmimeSigning().getPkcs12StoreData()).isNotNull();
        assertThat(email.getPkcs12ConfigForSmimeSigning().getStorePassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyAlias()).isEqualTo("smime_test_user_alias");
        assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyPassword()).isEqualTo("letmein".toCharArray());
    }

    @Test
    public void testSignWithSmime_WithConfigParameters() {
        builder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias", "letmein");

        final Email email = builder.buildEmail();

        assertThat(email.getPkcs12ConfigForSmimeSigning()).isNotNull();
        assertThat(email.getPkcs12ConfigForSmimeSigning().getPkcs12StoreData()).isNotNull();
        assertThat(email.getPkcs12ConfigForSmimeSigning().getStorePassword()).isEqualTo("letmein".toCharArray());
        assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyAlias()).isEqualTo("smime_test_user_alias");
        assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyPassword()).isEqualTo("letmein".toCharArray());
    }

    @Test
    public void testEncryptWithSmime_FromFile() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        builder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

        final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    @Test
    public void testEncryptWithSmime_FromFilePath() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        builder.encryptWithSmime(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt");

        final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    @Test
    public void testEncryptWithSmime_FromCertificate() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        X509Certificate certificateIn = CertificationUtil.readFromPem(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

        builder.encryptWithSmime(certificateIn);

        final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

        assertThat(certificateOut).isNotNull();
        assertSignedBy(certificateOut, "Benny Bottema");
    }

    private static void assertSignedBy(X509Certificate certificate, @SuppressWarnings("SameParameterValue") final String expectedSignedBy)
            throws OperatorCreationException {
        assertThat(extractSignedBy(certificate)).isEqualTo(expectedSignedBy);
    }
}