package org.simplejavamail.email.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

/** Real protected message/rfc822 header view with a deliberately conflicting public carrier. */
final class ProtectedHeaderFixture {
    private ProtectedHeaderFixture() { }

    static byte[] signed(final SimpleJavaMail mail, final Email authored) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = store(keys);
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator signer = new SMIMESignedGenerator();
        signer.addCertificates(new JcaCertStore(List.of(certificate)));
        signer.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(new BouncyCastleProvider())
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeMessage carrier = carrier();
        carrier.setContent(signer.generate(authoredPart(mail, authored)));
        carrier.saveChanges();
        return EmailConverter.mimeMessageToEMLByteArray(carrier);
    }

    static byte[] encrypted(final SimpleJavaMail mail, final Email authored) throws Exception {
        return encrypted(mail, authored, true);
    }

    static byte[] encrypted(final SimpleJavaMail mail, final Email authored, final boolean publicSubject) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final SmimeEncryptionConfig encryption = SmimeEncryptionConfig.builder()
                .x509Certificate((X509Certificate) store(keys).getCertificate(keys.getKeyAlias())).build();
        final MimeMessage carrier = carrier();
        if (!publicSubject) carrier.removeHeader("Subject");
        carrier.setContent(authoredMime(mail, authored), "message/rfc822");
        carrier.saveChanges();
        final Email settings = mail.emailBuilder().startingBlank().encryptWithSmime(encryption).buildEmail();
        return EmailConverter.mimeMessageToEMLByteArray(ModuleLoader.loadSmimeModule()
                .encryptMessageWithSmime(carrier.getSession(), settings, carrier, encryption));
    }

    private static MimeBodyPart authoredPart(final SimpleJavaMail mail, final Email authored) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setContent(authoredMime(mail, authored), "message/rfc822");
        return part;
    }

    private static MimeMessage authoredMime(final SimpleJavaMail mail, final Email authored) {
        final MimeMessage represented = mail.converter().fromEmail(authored).toMimeMessage();
        try {
            if (authored.getId() == null) represented.removeHeader("Message-ID");
            if (authored.getSentDate() == null) represented.removeHeader("Date");
            return represented;
        } catch (jakarta.mail.MessagingException failure) {
            throw new AssertionError(failure);
        }
    }

    private static MimeMessage carrier() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        final MimeMessage carrier = new MimeMessage(Session.getInstance(properties));
        carrier.setFrom("carrier@example.org");
        carrier.setRecipients(jakarta.mail.Message.RecipientType.TO, "carrier-recipient@example.org");
        carrier.setSubject("Public wrapper");
        carrier.addHeader("X-Route", "outer-one");
        carrier.addHeader("X-Route", "outer-two");
        carrier.addHeader("X-Outer", "outer-only");
        return carrier;
    }

    private static KeyStore store(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return store;
    }
}
