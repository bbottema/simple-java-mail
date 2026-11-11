package org.simplejavamail.examples.patterns;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEEnvelopedGenerator;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

/** Creates actual synthetic MIME inputs for the local lesson. No public inspection results are manufactured. */
public final class PartnerDocumentFixtures {
    private static final Provider CRYPTO = new BouncyCastleProvider();
    private static final byte[] DOCUMENT = "order=local-demo\namount=42\n".getBytes(StandardCharsets.UTF_8);
    private final SimpleJavaMail mail;
    private final Identity approved;
    private final Identity unapproved;

    public PartnerDocumentFixtures(SimpleJavaMail mail, Pkcs12Config approvedKeys, Pkcs12Config unapprovedKeys) throws Exception {
        this.mail = mail;
        approved = loadIdentity(approvedKeys);
        unapproved = loadIdentity(unapprovedKeys);
    }

    public byte[] message(String scenario) throws Exception {
        if (!List.of("approved", "unapproved", "unavailable-verification", "unavailable-decryption", "mixed", "outside-coverage").contains(scenario)) {
            throw new IllegalArgumentException("Choose an available partner-document scenario");
        }
        if ("outside-coverage".equals(scenario)) {
            byte[] protectedChild = encryptedSignedDocument("approved");
            Email outer = mail.emailBuilder().startingBlank().from("sender@example.test").to("reader@example.test")
                    .withSubject("An unsigned document beside a protected child")
                    .withPlainText("The independent child does not sign this parent document")
                    .withAttachment("partner-order.txt", DOCUMENT, "text/plain")
                    .withAttachment("protected-child.eml", protectedChild, "message/rfc822")
                    .buildEmail();
            return mail.converter().fromEmail(outer).toEmlBytes();
        }
        return encryptedSignedDocument(scenario);
    }

    private byte[] encryptedSignedDocument(String scenario) throws Exception {
        MimeBodyPart content = documentContent();
        SMIMESignedGenerator generator = new SMIMESignedGenerator();
        Identity signer = "unapproved".equals(scenario) ? unapproved : approved;
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(CRYPTO)
                .build("SHA256withRSA", signer.key(), signer.certificate()));
        if (!"unavailable-verification".equals(scenario)) {
            generator.addCertificates(new JcaCertStore(List.of(signer.certificate())));
        }
        if ("mixed".equals(scenario)) {
            generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(CRYPTO)
                    .build("SHA256withRSA", unapproved.key(), unapproved.certificate()));
            generator.addCertificates(new JcaCertStore(List.of(unapproved.certificate())));
        }
        MimeMultipart signed = generator.generate(content);
        if ("mixed".equals(scenario)) {
            invalidateOnlySecondSignature((MimeBodyPart) signed.getBodyPart(1));
        }
        MimeBodyPart signedEntity = new MimeBodyPart();
        signedEntity.setContent(signed);
        signedEntity.setHeader("Content-Type", signed.getContentType());

        SMIMEEnvelopedGenerator encryptor = new SMIMEEnvelopedGenerator();
        encryptor.addRecipientInfoGenerator(new JceKeyTransRecipientInfoGenerator(approved.certificate()).setProvider(CRYPTO));
        MimeBodyPart encrypted = encryptor.generate(signedEntity,
                new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).setProvider(CRYPTO).build());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.test");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.test");
        message.setSubject("Synthetic partner document", "UTF-8");
        message.setDataHandler(encrypted.getDataHandler());
        message.setHeader("Content-Type", encrypted.getContentType());
        message.saveChanges();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private MimeBodyPart documentContent() throws Exception {
        Email clear = mail.emailBuilder().startingBlank().from("sender@example.test").to("reader@example.test")
                .withSubject("Synthetic partner document").withPlainText("The attached order is the required business document")
                .withAttachment("partner-order.txt", DOCUMENT, "text/plain").buildEmail();
        MimeMessage rendered = mail.converter().fromEmail(clear).toMimeMessage();
        MimeBodyPart entity = new MimeBodyPart();
        entity.setDataHandler(rendered.getDataHandler());
        entity.setHeader("Content-Type", rendered.getContentType());
        return entity;
    }

    private void invalidateOnlySecondSignature(MimeBodyPart signaturePart) throws Exception {
        final byte[] encoded;
        try (var input = signaturePart.getInputStream()) {
            encoded = input.readAllBytes();
        }
        ContentInfo envelope = ContentInfo.getInstance(ASN1Primitive.fromByteArray(encoded));
        SignedData signed = SignedData.getInstance(envelope.getContent());
        ASN1EncodableVector signatures = new ASN1EncodableVector();
        for (int index = 0; index < signed.getSignerInfos().size(); index++) {
            SignerInfo signature = SignerInfo.getInstance(signed.getSignerInfos().getObjectAt(index));
            boolean selected = !signature.getSID().isTagged() && IssuerAndSerialNumber.getInstance(signature.getSID().getId())
                    .getSerialNumber().getValue().equals(unapproved.certificate().getSerialNumber());
            if (selected) {
                byte[] invalid = signature.getEncryptedDigest().getOctets().clone();
                invalid[invalid.length - 1] ^= 1;
                signature = new SignerInfo(signature.getSID(), signature.getDigestAlgorithm(), signature.getAuthenticatedAttributes(),
                        signature.getDigestEncryptionAlgorithm(), new DEROctetString(invalid), signature.getUnauthenticatedAttributes());
            }
            signatures.add(signature);
        }
        SignedData changed = new SignedData(signed.getDigestAlgorithms(), signed.getEncapContentInfo(), signed.getCertificates(),
                signed.getCRLs(), new DERSet(signatures));
        byte[] invalidSignature = new ContentInfo(envelope.getContentType(), changed).getEncoded();
        signaturePart.setDataHandler(new DataHandler(new ByteArrayDataSource(invalidSignature, "application/pkcs7-signature")));
        signaturePart.setHeader("Content-Transfer-Encoding", "base64");
    }

    private static Identity loadIdentity(Pkcs12Config config) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(config.getPkcs12StoreData()), config.getStorePassword());
        return new Identity((PrivateKey) store.getKey(config.getKeyAlias(), config.getKeyPassword()),
                (X509Certificate) store.getCertificate(config.getKeyAlias()));
    }

    private record Identity(PrivateKey key, X509Certificate certificate) { }
}
