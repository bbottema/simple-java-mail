package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;

public class ReadSmimeAttachmentsTest {
    private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";
    private static final String GENERATED_ADAPTER_ID = "<generated-for-smime-signed-attachment@simple-java-mail>";
    private static final String DOCUMENT = "3 crucial benefits of Cloud computing.docx";
    private static final String BODY = "Invio messaggio SMIME (signed and clear text)\n\n-- \nAlessandro Gasparini\nChief Technology Officer\n"
            + "\n\n\n\nLOGICALDOC Srl\nVia Aldo Moro interna 3\n41012 Carpi / Modena\n\nP: +39 059 5970906\n"
            + " <https://www.logicaldoc.com> https://www.logicaldoc.com \n\n \n\n";

    @Test
    public void nativeAndEmlImportsExposeOneReadableMessageAndRetainTheirActualOriginals() throws Exception {
        Email nativeMessage = EmailConverter.outlookMsgToEmail(fixture("msg"));
        Email emlMessage = EmailConverter.emlToEmail(fixture("eml"));
        assertThat(nativeMessage.getFromRecipient()).isNull();
        assertThat(nativeMessage.getSubject()).isEqualTo("Invio messaggio SMIME (signed and clear text)");
        assertThat(nativeMessage.getRecipients()).containsExactly(new Recipient("a.gasparini@logicaldoc.com", "a.gasparini@logicaldoc.com", TO, null));
        assertReadableContent(nativeMessage);
        assertReadableContent(emlMessage);
        assertThat(nativeMessage.getId()).isNotEqualTo(GENERATED_ADAPTER_ID);
        if (nativeMessage.getHeaders().get("Message-ID") != null) {
            assertThat(nativeMessage.getHeaders().get("Message-ID")).doesNotContain(GENERATED_ADAPTER_ID);
        }
        assertThat(nativeMessage.getAttachments().get(0).readAllBytes()).containsExactly(emlMessage.getAttachments().get(0).readAllBytes());
        assertThat(new ContentType(nativeMessage.getAttachments().get(0).getDataSource().getContentType()).getBaseType())
                .isEqualTo(new ContentType(emlMessage.getAttachments().get(0).getDataSource().getContentType()).getBaseType());
        SmimeInspectionAssertions.assertOriginal(nativeMessage, Files.readAllBytes(fixture("msg").toPath()));
        SmimeInspectionAssertions.assertOriginal(emlMessage, Files.readAllBytes(fixture("eml").toPath()));

        byte[] msgCms = nativeOpaquePayload(nativeMessage.getInspection().getOriginalMessage().readAllBytes());
        MimeMessage originalEml = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(emlMessage.getInspection().getOriginalMessage().readAllBytes()));
        ContentType type = new ContentType(originalEml.getContentType());
        assertThat(type.getBaseType()).isEqualTo("application/pkcs7-mime");
        assertThat(type.getParameter("smime-type")).isEqualTo("signed-data");
        assertThat(type.getParameter("name")).isEqualTo("smime.p7m");
        try (InputStream input = originalEml.getInputStream()) {
            byte[] emlCms = input.readAllBytes();
            assertThat(emlCms).containsExactly(msgCms);
        }
        assertThat(msgCms).hasSize(28116);
        assertThat(String.format("%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(msgCms))))
                .isEqualTo("e764a65910965b7035561d4a9d67eccec92c325d7acce364a9db171f2858fb08");
    }

    @Test
    public void emlSignatureRecognitionHasTheActualSignerAndSelectedFileCoverage() {
        Email received = EmailConverter.emlToEmail(fixture("eml"));
        assertReadableContent(received);
        SmimeInspectionAssertions.assertSignatureObservation(received, SignatureVerification.VERIFIED);
        assertThat(received.getInspection().getSmime().getSignatures()).singleElement().satisfies(signature ->
                assertThat(signature.getSignerName()).contains("shatzing5@outlook.com"));
        assertThat(received.getInspection().getAttachment(0).getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
    }

    @Test
    public void copiesAndExplicitEditsKeepHistoricalInputWithoutRestoringOldContent() {
        SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        Email received = EmailConverter.outlookMsgToEmail(fixture("msg"));
        Email copied = mail.emailBuilder().copying(received).buildEmail();
        assertThat(copied).isEqualTo(received);
        assertReadableContent(copied);
        byte[] original = received.getInspection().getOriginalMessage().readAllBytes();
        Email edited = mail.emailBuilder().copying(copied).withPlainText("Caller replacement").buildEmail();
        assertThat(edited.getPlainText()).isEqualTo("Caller replacement");
        assertThat(edited.getInspection().getPlainText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(edited.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(edited.getHTMLText()).isEqualTo(received.getHTMLText());
        assertThat(edited.getInspection().getHTMLText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        SmimeInspectionAssertions.assertOriginal(edited, original);
        assertReadableContent(received);
    }

    private static void assertReadableContent(Email email) {
        assertThat(normalizeNewlines(email.getPlainText())).isEqualTo(BODY);
        assertThat(email.getHTMLText()).contains("<p class=MsoNormal><span lang=EN-US>Invio messaggio SMIME (signed and clear text)<o:p>");
        assertThat(email.getEmbeddedImages()).isEmpty();
        assertThat(email.getAttachments()).extracting("name").containsExactly(DOCUMENT);
        assertThat(email.getAttachments().get(0).getEmail()).isNull();
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getInspection().getHTMLText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
    }

    private static byte[] nativeOpaquePayload(byte[] original) throws Exception {
        List<byte[]> payloads = new ArrayList<>();
        try (POIFSFileSystem source = new POIFSFileSystem(new ByteArrayInputStream(original))) {
            collectAttachmentData(source.getRoot(), payloads);
        }
        assertThat(payloads).hasSize(1);
        return payloads.get(0);
    }

    private static void collectAttachmentData(DirectoryNode node, List<byte[]> payloads) throws Exception {
        if (node.getName().startsWith("__attach_version1.0_") && node.hasEntry("__substg1.0_37010102")) {
            try (InputStream input = node.createDocumentInputStream("__substg1.0_37010102")) { payloads.add(input.readAllBytes()); }
        }
        for (Entry child : node) if (child.isDirectoryEntry()) collectAttachmentData((DirectoryNode) child, payloads);
    }

    private static File fixture(String extension) { return new File(RESOURCE_FOLDER + "/SMIME (signed and clear text)." + extension); }
}
