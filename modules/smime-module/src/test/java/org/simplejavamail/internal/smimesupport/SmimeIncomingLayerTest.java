package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmimeIncomingLayerTest {
    private static final Session SESSION = Session.getInstance(new Properties());
    private static Identity first;
    private static Identity second;

    @BeforeAll
    static void createSyntheticKeys() throws Exception {
        first = identity("Approved partner", 1);
        second = identity("Other partner", 2);
    }

    @Test
    void oneValidAndOneInvalidSignatureRemainSeparateWhileContentStaysReadable() throws Exception {
        final MimeMultipart signed = signed(true, first, second);
        corruptOneSignature(signed);
        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, message(signed), null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.isInspectionComplete()).isTrue();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("A partner document");
        assertThat(opened.getSignatures()).extracting(signature -> signature.getStatus())
                .containsExactlyInAnyOrder(SignatureVerification.VERIFIED, SignatureVerification.INVALID);
        assertThat(opened.getSignatures()).extracting(signature -> signature.getSigningKeyFingerprint())
                .doesNotContainNull().doesNotHaveDuplicates();
        assertThat(opened.getSignatures()).extracting(signature -> signature.getReference()).doesNotHaveDuplicates();
        assertThat(opened.getSignatures()).allSatisfy(signature ->
                assertThat(signature.getSignerKeyId()).matches(".+;serial=[0-9A-F]+").doesNotContain("@"));
    }

    @Test
    void unavailableCertificateDoesNotTurnReadableContentIntoAnInvalidSignature() throws Exception {
        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, message(signed(false, first)), null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("A partner document");
        assertThat(opened.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.UNVERIFIED);
            assertThat(signature.getSigningKeyFingerprint()).isNull();
            assertThat(signature.getFailureReason()).contains("certificate");
        });
    }

    @Test
    void binaryFileDecryptionKeepsItsActualMetadataAndDoesNotInventAnAttachedMessage() throws Exception {
        final byte[] document = "%PDF-1.7\nsynthetic partner document\n".getBytes(StandardCharsets.US_ASCII);
        final MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(document, "application/pdf")));
        file.setFileName("invoice.pdf");
        final MimeMessage encrypted = message(SmimeCryptoSupport.encrypt(file, first.certificate,
                KeyEncapsulationAlgorithm.RSA, CMSAlgorithm.AES256_CBC));

        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, encrypted, first.keys);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.isAuthoredHeaders()).isFalse();
        assertThat(opened.getOpenedEntity().getFileName()).isEqualTo("invoice.pdf");
        assertThat(opened.getOpenedEntity().isMimeType("application/pdf")).isTrue();
        try (var payload = opened.getOpenedEntity().getInputStream()) {
            assertThat(payload.readAllBytes()).containsExactly(document);
        }
        assertThat(opened.getDecryptions()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            assertThat(attempt.getEncryptionAlgorithm()).isEqualTo(CMSAlgorithm.AES256_CBC.getId());
        });
    }

    @Test
    void nonmatchingLoadedKeyIsUnavailableButBadConfiguredPasswordRemainsAnOperationFailure() throws Exception {
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Protected content", "UTF-8");
        final MimeMessage encrypted = message(SmimeCryptoSupport.encrypt(text, first.certificate,
                KeyEncapsulationAlgorithm.RSA, CMSAlgorithm.AES256_CBC));
        final ProtectionLayerResult wrongRecipient = module().openIncomingLayer(SESSION, encrypted, second.keys);

        assertThat(wrongRecipient.isOpened()).isFalse();
        assertThat(wrongRecipient.getEffectiveMimeMessage()).isSameAs(encrypted);
        assertThat(wrongRecipient.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));

        final Pkcs12Config badPassword = Pkcs12Config.builder().pkcs12Store(first.keys.getPkcs12StoreData())
                .storePassword("incorrect".toCharArray()).keyAlias(first.keys.getKeyAlias())
                .keyPassword(first.keys.getKeyPassword()).build();
        assertThatThrownBy(() -> module().openIncomingLayer(SESSION, encrypted, badPassword))
                .isInstanceOf(MailException.class).hasMessageContaining("PKCS12");
    }

    @Test
    void openingOneEnvelopeDoesNotSecretlyProcessTheNextProtectionLayer() throws Exception {
        final MimeBodyPart signed = new MimeBodyPart();
        final MimeMultipart signedContent = signed(true, first);
        signed.setContent(signedContent);
        signed.setHeader("Content-Type", signedContent.getContentType());
        final MimeMessage encrypted = message(SmimeCryptoSupport.encrypt(signed, first.certificate,
                KeyEncapsulationAlgorithm.RSA, CMSAlgorithm.AES256_CBC));
        final SMIMEModule module = module();

        final ProtectionLayerResult outer = module.openIncomingLayer(SESSION, encrypted, first.keys);
        assertThat(outer.isOpened()).isTrue();
        assertThat(outer.getSignatures()).isEmpty();
        assertThat(outer.getEffectiveMimeMessage().isMimeType("multipart/signed")).isTrue();
        final ProtectionLayerResult inner = module.openIncomingLayer(SESSION, outer.getEffectiveMimeMessage(), first.keys);
        assertThat(inner.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(inner.getDecryptions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivedSignedBytesKeepTheirLineEndingsWithoutBeingRewrittenByInspection(final boolean lineFeedOnly) throws Exception {
        final byte[] canonical = serialized(message(signed(true, first)));
        final byte[] input = lineFeedOnly ? new String(canonical, StandardCharsets.ISO_8859_1)
                .replace("\r\n", "\n").getBytes(StandardCharsets.ISO_8859_1) : canonical;
        final MimeMessage original = FinalizedMimeMessage.fromMessageBytes(SESSION, input, FinalizedMimeMessage.ProtectionState.NONE);

        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, original, null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(((FinalizedMimeMessage) original).getSerializedBytes()).containsExactly(input);
    }

    @Test
    void unsupportedSignatureAlgorithmIsUnverifiedWhileReadableContentRemainsAvailable() throws Exception {
        final MimeMultipart multipart = signed(true, first);
        final ContentInfo original = ContentInfo.getInstance(new SMIMESigned(multipart).getEncoded());
        final SignedData data = SignedData.getInstance(original.getContent());
        final SignerInfo signer = SignerInfo.getInstance(data.getSignerInfos().getObjectAt(0));
        final SignerInfo unsupported = new SignerInfo(signer.getSID(), signer.getDigestAlgorithm(), signer.getAuthenticatedAttributes(),
                new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.3.4.5.6.7.8")), signer.getEncryptedDigest(), signer.getUnauthenticatedAttributes());
        replaceSignaturePart(multipart, original, data, new DERSet(unsupported));

        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, message(multipart), null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.UNVERIFIED));
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("A partner document");
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed-data", "enveloped-data"})
    void corruptCmsInputIsARecognizedProcessingFailure(final String kind) throws Exception {
        final byte[] prefix = ("Content-Type: application/pkcs7-mime; smime-type=" + kind + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        malformed.write(prefix);
        malformed.write(new byte[]{0x30, (byte) 0x80, 0x30, 1, 0, 0, 0});
        final MimeMessage original = FinalizedMimeMessage.fromMessageBytes(SESSION, malformed.toByteArray(),
                FinalizedMimeMessage.ProtectionState.NONE);

        final ProtectionLayerResult result = module().openIncomingLayer(SESSION, original, first.keys);

        assertThat(result.isRecognized()).isTrue();
        assertThat(result.isOpened()).isFalse();
        assertThat(result.getEffectiveMimeMessage()).isSameAs(original);
        assertThat(result.getFailureReason()).isNotEmpty();
        assertThat(result.getInputReadFailure()).isNull();
        assertThat(result.getInputCloseFailure()).isNull();
    }

    @Test
    void cancellationAndUnrelatedProviderDefectsKeepTheirOperationBoundary() {
        final MimeMessage interrupted = new MimeMessage(SESSION) {
            @Override public String getContentType() throws jakarta.mail.MessagingException {
                throw new jakarta.mail.MessagingException("interrupted synthetic source", new java.io.InterruptedIOException());
            }
        };
        assertThatThrownBy(() -> module().openIncomingLayer(SESSION, interrupted, null)).isInstanceOf(CancellationException.class);
        final IllegalStateException providerDefect = new IllegalStateException("synthetic provider defect");
        final MimeMessage brokenProvider = new MimeMessage(SESSION) {
            @Override public String getContentType() { throw providerDefect; }
        };
        assertThatThrownBy(() -> module().openIncomingLayer(SESSION, brokenProvider, null)).isSameAs(providerDefect);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pkcs7-mime", "Application/PKCS7-Mime", "application/x-pkcs7-mime"})
    void actualBareCmsEnvelopesStillDecryptWithoutGuessingTheirKindFromTheMediaType(final String mediaType) throws Exception {
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Bare encrypted payload", "UTF-8");
        final MimeBodyPart protectedPart = SmimeCryptoSupport.encrypt(text, first.certificate,
                KeyEncapsulationAlgorithm.RSA, CMSAlgorithm.AES256_CBC);
        protectedPart.setHeader("Content-Type", mediaType);
        final MimeMessage received = message(protectedPart);
        final byte[] input = serialized(received);
        final SMIMEModule module = module();

        final ProtectionLayerResult opened = module.openIncomingLayer(SESSION, received, first.keys);
        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("Bare encrypted payload");
        assertThat(opened.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED));
        final ProtectionLayerResult unavailable = module.openIncomingLayer(SESSION, received, null);
        assertThat(unavailable.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertThat(((FinalizedMimeMessage) received).getSerializedBytes()).containsExactly(input);
    }

    @Test
    void bareOpaqueSignedDataUsesItsActualCmsKindWithoutLoadingADecryptionKey() throws Exception {
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(first.certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", first.pair.getPrivate(), first.certificate));
        final MimeBodyPart text = new MimeBodyPart(); text.setText("Bare signed payload", "UTF-8");
        final MimeBodyPart signed = generator.generateEncapsulated(text);
        signed.setHeader("Content-Type", "application/pkcs7-mime");

        final ProtectionLayerResult opened = module().openIncomingLayer(SESSION, message(signed), null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("Bare signed payload");
        assertThat(opened.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(opened.getDecryptions()).isEmpty();
    }

    @Test
    void aBareMediaTypeWithUnclassifiableBytesDoesNotInventAnEncryptedOrSignedOccurrence() throws Exception {
        final byte[] input = "Content-Type: application/pkcs7-mime\r\n\r\nnot CMS".getBytes(StandardCharsets.US_ASCII);
        final MimeMessage received = FinalizedMimeMessage.fromMessageBytes(SESSION, input, FinalizedMimeMessage.ProtectionState.NONE);

        final ProtectionLayerResult result = module().openIncomingLayer(SESSION, received, null);

        assertThat(result.isRecognized()).isTrue();
        assertThat(result.isOpened()).isFalse();
        assertThat(result.isInspectionComplete()).isFalse();
        assertThat(result.getDecryptions()).isEmpty();
        assertThat(result.getSignatures()).isEmpty();
        assertThat(result.getFailureReason()).isNotEmpty();
        assertThat(result.getInputReadFailure()).isNull();
        assertThat(result.getInputCloseFailure()).isNull();
    }

    @Test
    void renewedCertificateChangesTheObservedCmsSelectorWhileKeepingTheActualSigningKeyIdentity() throws Exception {
        final X509Certificate renewedCertificate = certificate(first.pair, first.certificate.getSubjectX500Principal(), 42);
        final Identity renewed = new Identity(first.pair, renewedCertificate, first.keys);
        final var before = module().openIncomingLayer(SESSION, message(signed(true, first)), null).getSignatures().get(0);
        final var after = module().openIncomingLayer(SESSION, message(signed(true, renewed)), null).getSignatures().get(0);

        assertThat(before.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
        assertThat(after.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
        assertThat(after.getSignerKeyId()).isEqualTo(renewedCertificate.getIssuerX500Principal().getName() + ";serial=2A");
        assertThat(after.getSignerKeyId()).isNotEqualTo(before.getSignerKeyId());
        assertThat(after.getSigningKeyFingerprint()).isEqualTo(before.getSigningKeyFingerprint());
    }

    @Test
    void aSubjectKeyIdentifierSignerUsesItsActualHexadecimalSelector() throws Exception {
        final byte[] identifier = new JcaX509ExtensionUtils().createSubjectKeyIdentifier(first.pair.getPublic()).getKeyIdentifier();
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(first.certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", first.pair.getPrivate(), identifier));
        final MimeBodyPart body = new MimeBodyPart(); body.setText("SKI signer", "UTF-8");

        final var observed = module().openIncomingLayer(SESSION, message(generator.generate(body)), null).getSignatures().get(0);

        assertThat(observed.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
        assertThat(observed.getSignerKeyId()).isEqualTo(org.bouncycastle.util.encoders.Hex.toHexString(identifier).toUpperCase(java.util.Locale.ROOT));
        assertThat(observed.getSignerKeyId()).matches("[0-9A-F]+");
    }

    @Test
    void anUnsupportedSignerPublicKeyLeavesReadableContentAndAnUnverifiedObservation() throws Exception {
        final var known = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(first.pair.getPublic().getEncoded());
        final var unknown = new org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(
                new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.3.4.5.6.7.9")), known.getPublicKeyData().getBytes());
        final var name = new org.bouncycastle.asn1.x500.X500Name(first.certificate.getSubjectX500Principal().getName());
        final X509Certificate unsupported = new JcaX509CertificateConverter().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .getCertificate(new org.bouncycastle.cert.X509v3CertificateBuilder(name, first.certificate.getSerialNumber(),
                        first.certificate.getNotBefore(), first.certificate.getNotAfter(), name, unknown)
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(SmimeCryptoSupport.BOUNCY_CASTLE).build(first.pair.getPrivate())));
        assertThat(unsupported.getPublicKey()).as("native unsupported-key fixture").isNull();
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(unsupported)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", first.pair.getPrivate(), first.certificate));
        final MimeBodyPart body = new MimeBodyPart(); body.setText("Readable despite unsupported signer key", "UTF-8");

        final var opened = module().openIncomingLayer(SESSION, message(generator.generate(body)), null);

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("Readable despite unsupported signer key");
        assertThat(opened.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.UNVERIFIED);
            assertThat(signature.getSigningKeyFingerprint()).isNull();
            assertThat(signature.getFailureReason()).contains("public key");
        });
    }

    private static SMIMEModule module() { return new SMIMESupport().createRuntime(4); }

    private static MimeMultipart signed(final boolean includeCertificates, final Identity... identities) throws Exception {
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        final List<X509Certificate> certificates = new ArrayList<>();
        for (Identity identity : identities) {
            certificates.add(identity.certificate);
            generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                    .build("SHA256withRSA", identity.pair.getPrivate(), identity.certificate));
        }
        if (includeCertificates) generator.addCertificates(new JcaCertStore(certificates));
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("A partner document", "UTF-8");
        return generator.generate(body);
    }

    private static void corruptOneSignature(final MimeMultipart multipart) throws Exception {
        final ContentInfo original = ContentInfo.getInstance(new SMIMESigned(multipart).getEncoded());
        final SignedData data = SignedData.getInstance(original.getContent());
        final ASN1Encodable[] signers = data.getSignerInfos().toArray();
        final SignerInfo signer = SignerInfo.getInstance(signers[0]);
        final byte[] signature = signer.getEncryptedDigest().getOctets();
        signature[signature.length - 1] ^= 1;
        signers[0] = new SignerInfo(signer.getSID(), signer.getDigestAlgorithm(), signer.getAuthenticatedAttributes(),
                signer.getDigestEncryptionAlgorithm(), new DEROctetString(signature), signer.getUnauthenticatedAttributes());
        replaceSignaturePart(multipart, original, data, new DERSet(signers));
    }

    private static void replaceSignaturePart(final MimeMultipart multipart, final ContentInfo original,
            final SignedData data, final org.bouncycastle.asn1.ASN1Set signers) throws Exception {
        final SignedData corrupted = new SignedData(data.getDigestAlgorithms(), data.getEncapContentInfo(), data.getCertificates(),
                data.getCRLs(), signers);
        multipart.getBodyPart(1).setDataHandler(new DataHandler(new ByteArrayDataSource(
                new ContentInfo(original.getContentType(), corrupted).getEncoded(), "application/pkcs7-signature")));
    }

    private static MimeMessage message(final MimeMultipart content) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setContent(content);
        part.setHeader("Content-Type", content.getContentType());
        return message(part);
    }

    private static MimeMessage message(final MimeBodyPart entity) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entity.writeTo(bytes);
        return FinalizedMimeMessage.fromMessageBytes(SESSION, bytes.toByteArray(), FinalizedMimeMessage.ProtectionState.NONE);
    }

    private static Identity identity(final String name, final int serial) throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair pair = generator.generateKeyPair();
        final X500Principal subject = new X500Principal("CN=" + name);
        final X509Certificate certificate = certificate(pair, subject, serial);
        final char[] password = "synthetic-test-password".toCharArray();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry("partner", pair.getPrivate(), password, new X509Certificate[]{certificate});
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        store.store(encoded, password);
        final Pkcs12Config keys = Pkcs12Config.builder().pkcs12Store(encoded.toByteArray()).storePassword(password)
                .keyAlias("partner").keyPassword(password).build();
        return new Identity(pair, certificate, keys);
    }

    private static X509Certificate certificate(final KeyPair pair, final X500Principal subject, final int serial) throws Exception {
        final Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        final Date notAfter = new Date(System.currentTimeMillis() + 31_536_000_000L);
        return new JcaX509CertificateConverter().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .getCertificate(new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(serial), notBefore, notAfter, subject, pair.getPublic())
                        .addExtension(org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier, false,
                                new JcaX509ExtensionUtils().createSubjectKeyIdentifier(pair.getPublic()))
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(SmimeCryptoSupport.BOUNCY_CASTLE).build(pair.getPrivate())));
    }

    private static final class Identity {
        final KeyPair pair;
        final X509Certificate certificate;
        final Pkcs12Config keys;

        private Identity(final KeyPair pair, final X509Certificate certificate, final Pkcs12Config keys) {
            this.pair = pair;
            this.certificate = certificate;
            this.keys = keys;
        }
    }

    private static byte[] serialized(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return bytes.toByteArray();
    }
}
