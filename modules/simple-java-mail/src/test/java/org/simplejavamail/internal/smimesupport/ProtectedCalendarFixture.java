package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.MailerHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;

/** Real S/MIME payload in a native MSG carrier, following the existing Outlook integration-fixture technique. */
public final class ProtectedCalendarFixture {
    public static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    public static final String TEXT = "Protected meeting description";
    public static final String HTML = "<p>Protected meeting description</p>";
    public static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//SJM Merge Contract//EN\r\n"
            + "METHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:merge-calendar@example.org\r\n"
            + "DTSTAMP:20261103T090000Z\r\nDTSTART:20261104T100000Z\r\nDTEND:20261104T103000Z\r\n"
            + "SUMMARY:Merge contract review\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    private ProtectedCalendarFixture() { }

    public static Pkcs12Config keys(Path resources) {
        return Pkcs12Config.builder().pkcs12Store(resources.resolve("pkcs12/smime_keystore.pkcs12").toString())
                .storePassword("letmein").keyAlias("smime_test_user_alias_rsa").keyPassword("letmein").build();
    }

    public static byte[] encryptedEml(Path resources) throws Exception {
        Pkcs12Config keys = keys(resources);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        Email clear = MAIL.emailBuilder().startingBlank()
                .from("payload-sender@example.org").to("payload-recipient@example.org")
                .withSubject("Protected calendar fixture").withPlainText(TEXT).withHTMLText(HTML)
                .withCalendarText(CalendarMethod.REQUEST, CALENDAR)
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
                        .withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                        .withCalendarTextContentTransferEncoding(ContentTransferEncoding.BASE_64).build()).buildEmail();
        Email protection = MAIL.emailBuilder().startingBlank()
                .fixingMessageId("<protected-calendar-fixture@example.org>")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build())
                .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate).build()).buildEmail();
        MimeMessage protectedMessage = MailerHelper.signAndOrEncryptMessageWithSmime(
                Session.getInstance(new Properties()), MAIL.converter().fromEmail(clear).toMimeMessage(), protection);
        return MAIL.converter().fromMimeMessage(protectedMessage).toEmlBytes();
    }

    public static byte[] outlook(Path resources, byte[] encryptedEml) throws Exception {
        MimeMessage encrypted = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(encryptedEml));
        final byte[] payload;
        try (InputStream input = encrypted.getInputStream()) { payload = input.readAllBytes(); }
        Path carrier = resources.resolve("pkcs12/test messages/S_MIME test message encrypted.msg");
        try (InputStream input = Files.newInputStream(carrier); POIFSFileSystem msg = new POIFSFileSystem(input)) {
            int replacements = replace(msg.getRoot(), payload);
            if (replacements != 1) throw new IllegalStateException("Expected one native encrypted MSG attachment, got " + replacements);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            msg.writeFilesystem(bytes);
            return bytes.toByteArray();
        }
    }

    private static int replace(DirectoryNode node, byte[] payload) throws Exception {
        int replaced = 0;
        if (node.getName().startsWith("__attach_version1.0_") && node.hasEntry("__substg1.0_37010102")) {
            node.createOrUpdateDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
            final byte[] properties;
            try (InputStream input = node.createDocumentInputStream("__properties_version1.0")) { properties = input.readAllBytes(); }
            ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 8; offset + 16 <= properties.length; offset += 16) {
                if (records.getInt(offset) == 0x37010102) records.putInt(offset + 8, payload.length);
            }
            node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
            replaced++;
        }
        for (Entry child : node) if (child.isDirectoryEntry()) replaced += replace((DirectoryNode) child, payload);
        return replaced;
    }
}
