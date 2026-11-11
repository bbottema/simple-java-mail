package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static org.simplejavamail.api.email.SignatureVerification.VERIFIED;
import static org.simplejavamail.api.email.SignatureVerification.INVALID;
import static org.simplejavamail.api.email.SignatureVerification.UNVERIFIED;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;

class SmimeSignedOutlookOutcomeTest {
    private static final String ORIGINAL_BODY = "Original signed-only Outlook body";
    private static final String TAMPERED_BODY = "Tampered signed-only Outlook body";

    @ParameterizedTest
    @MethodSource("signatureCases")
    void signedOnlyOutlookImportsKeepTheirActualVerificationOutcome(
            final SignatureVerification expected) throws Exception {
        final byte[] source = signedOutlookMessage(expected);
        final BorrowedInput fluentInput = new BorrowedInput(source);
        final EmailPopulatingBuilder fluent = SimpleJavaMail.fromDefaults().converter().fromOutlookMsg(fluentInput).toEmailBuilder();
        final BorrowedInput staticInput = new BorrowedInput(source);
        final EmailPopulatingBuilder conventional = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(staticInput).getEmailBuilder();
        final Email imported = fluent.buildEmail();
        final Email staticImport = conventional.buildEmail();

        assertThat(fluentInput.closed).as("fluent import borrows the supplied stream").isFalse();
        assertThat(staticInput.closed).as("static MSG import closes its supplied stream").isTrue();
        for (Email email : List.of(imported, staticImport,
                SimpleJavaMail.fromDefaults().emailBuilder().copying(imported).buildEmail(), roundTrip(imported))) {
            assertFacts(email, expected);
            final String text = expected == INVALID ? TAMPERED_BODY : ORIGINAL_BODY;
            assertThat(email.getPlainText()).contains(text);
            assertOriginal(email, source);
        }
    }

    private static Stream<Arguments> signatureCases() {
        return Stream.of(VERIFIED, INVALID, UNVERIFIED).map(Arguments::of);
    }

    @Test
    void inspectingASignedOutlookEnvelopeProcessesOneLayerAndReusesCapturedFacts() throws Exception {
        final byte[] source = signedOutlookMessage(VERIFIED);
        final SmimeIncomingLayer actual = new SmimeIncomingLayer(keys -> { throw new AssertionError("Signed-only input needs no decryption key"); });
        try (MockedConstruction<SmimeIncomingLayer> layers = Mockito.mockConstruction(SmimeIncomingLayer.class, (layer, context) ->
                Mockito.when(layer.open(any(), any(), any())).thenAnswer(call -> actual.open(call.getArgument(0), call.getArgument(1), call.getArgument(2))))) {
            final Email email = SimpleJavaMail.withConfig(testutil.ConfigLoaderTestHelper.emptyConfig()).converter()
                    .fromOutlookMsg(new ByteArrayInputStream(source)).toEmail();
            assertFacts(email, VERIFIED);
            final var observation = email.getInspection().getSmime().getSignatures().get(0);
            for (int index = 0; index < 3; index++) {
                assertThat(email.getInspection().getPlainText().getSignatures()).singleElement().satisfies(signature ->
                        assertThat(signature.getObservation()).isSameAs(observation));
            }
            assertThat(layers.constructed()).hasSize(1);
            Mockito.verify(layers.constructed().get(0), times(1)).open(any(), any(), any());
        }
    }

    @ParameterizedTest
    @MethodSource("independentCases")
    void independentSignedAttachmentsCannotSupplyTheirPlainParentsVerificationResult(
            final SignatureVerification signatureStatus) throws Exception {
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Plain owning message", "UTF-8");
        final MimeBodyPart protectedFile = new MimeBodyPart();
        protectedFile.setDataHandler(new DataHandler(new ByteArrayDataSource(opaqueSignedPayload(signatureStatus),
                "application/pkcs7-mime; smime-type=signed-data")));
        protectedFile.setFileName("signed-file.p7m");
        final MimeBodyPart note = new MimeBodyPart();
        note.setText("Independent note", "UTF-8");
        note.setFileName("note.txt");
        final MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        multipart.addBodyPart(protectedFile);
        multipart.addBodyPart(note);
        final MimeMessage parent = new MimeMessage(Session.getInstance(new Properties()));
        parent.setFrom("sender@example.org");
        parent.setContent(multipart);
        parent.saveChanges();

        final Email email = EmailConverter.mimeMessageToEmail(parent);

        assertThat(email.getPlainText()).contains("Plain owning message");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getAttachments()).hasSize(2);
        assertThat(email.getAttachments().get(0).getEmail()).isNull();
        assertThat(email.getInspection().getAttachment(0).getSignatureStatus()).isEqualTo(SignatureStatus.valueOf(signatureStatus.name()));
    }

    private static Stream<Arguments> independentCases() {
        return Stream.of(VERIFIED, INVALID, UNVERIFIED).map(Arguments::of);
    }

    private static void assertFacts(final Email email, final SignatureVerification expected) {
        assertPlainProtection(email, SignatureStatus.valueOf(expected.name()), EncryptionStatus.NOT_ENCRYPTED);
        assertSignatureObservation(email, expected);
        assertThat(email.getInspection().getSmime().getDecryptions()).isEmpty();
        assertThat(email.getInspection().getOriginalMessage()).as("actual MSG input is retained in its own format").isNotNull();
        assertThat(email.getInspection().getOriginalMessage().getName()).endsWith(".msg");
        assertThat(email.getInspection().getSmime().getSignatures()).singleElement().satisfies(signature -> {
            if (expected == VERIFIED) assertThat(signature.getFailureReason()).isNull();
            else assertThat(signature.getFailureReason()).isNotEmpty();
        });
    }

    private static byte[] signedOutlookMessage(final SignatureVerification expected) throws Exception {
        final Path template = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message encrypted.msg");
        final byte[] signedPayload = opaqueSignedPayload(expected);
        try (InputStream input = Files.newInputStream(template); POIFSFileSystem file = new POIFSFileSystem(input)) {
            assertThat(replaceAttachment(file.getRoot(), signedPayload)).isEqualTo(1);
            final String headersName = "__substg1.0_007D001F";
            final String headers;
            try (InputStream headersInput = file.getRoot().createDocumentInputStream(headersName)) {
                headers = new String(headersInput.readAllBytes(), UTF_16LE);
            }
            assertThat(headers).contains("enveloped-data");
            final byte[] signedHeaders = headers.replace("enveloped-data", "signed-data").getBytes(UTF_16LE);
            file.getRoot().createOrUpdateDocument(headersName, new ByteArrayInputStream(signedHeaders));
            updateStreamLength(file.getRoot(), 32, 0x007D001F, signedHeaders.length);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static byte[] opaqueSignedPayload(final SignatureVerification expected) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        if (expected != UNVERIFIED) {
            generator.addCertificates(new JcaCertStore(List.of(certificate)));
        }
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText(ORIGINAL_BODY, "UTF-8");
        final MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(text);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(multipart);
        final MimeBodyPart signed = generator.generateEncapsulated(content);
        final String payload;
        try (InputStream input = signed.getInputStream()) {
            payload = new String(input.readAllBytes(), ISO_8859_1);
        }
        assertThat(payload).contains(ORIGINAL_BODY);
        return (expected == INVALID ? payload.replace(ORIGINAL_BODY, TAMPERED_BODY) : payload).getBytes(ISO_8859_1);
    }

    private static int replaceAttachment(final DirectoryNode node, final byte[] payload) throws Exception {
        int replaced = 0;
        if (node.getName().startsWith("__attach_version1.0_") && node.hasEntry("__substg1.0_37010102")) {
            node.createOrUpdateDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
            updateStreamLength(node, 8, 0x37010102, payload.length);
            replaced++;
        }
        for (Entry entry : node) {
            if (entry.isDirectoryEntry()) {
                replaced += replaceAttachment((DirectoryNode) entry, payload);
            }
        }
        return replaced;
    }

    private static void updateStreamLength(final DirectoryNode node, final int headerLength, final int propertyTag,
            final int length) throws Exception {
        final byte[] properties;
        try (InputStream input = node.createDocumentInputStream("__properties_version1.0")) {
            properties = input.readAllBytes();
        }
        final ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
        boolean found = false;
        for (int offset = headerLength; offset + 16 <= properties.length; offset += 16) {
            if (records.getInt(offset) == propertyTag) {
                records.putInt(offset + 8, length);
                found = true;
            }
        }
        assertThat(found).as("the fixture contains the changed stream's MAPI property").isTrue();
        node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;

        private BorrowedInput(final byte[] bytes) { super(bytes); }

        @Override public void close() { closed = true; }
    }
}
