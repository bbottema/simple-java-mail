package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;

/** Real detached signatures and encrypted child MIME, through supported import entry points. */
@Timeout(30)
class DetachedSignedParentAttachmentTest {
    private static final String BODY = "Parent text stays with its signed message.";
    private static final String HTML = "<p>Parent HTML stays with its signed message.</p>";
    private static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//SJM attachment regression//EN\r\n"
            + "METHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:parent-event@example.test\r\n"
            + "DTSTAMP:20261105T000000Z\r\nDTSTART:20261106T120000Z\r\nSUMMARY:Parent event\r\n"
            + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    private static final String PROTECTED_NAME = "independent.p7m";
    private static final String SIGNATURE_NAME = "parent-signature.p7s";
    private static final String NOTE_NAME = "note.txt";

    private enum Route {
        FLUENT_MIME_EAGER, FLUENT_MIME_LAZY, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
        STATIC_MIME_EAGER, STATIC_MIME_LAZY, STATIC_EML
    }

    static Stream<Arguments> imports() {
        return Stream.of(Route.values()).flatMap(route -> Stream.of(false, true)
                .map(note -> Arguments.of(route, note)));
    }

    @ParameterizedTest(name = "{0}, ordinary sibling={1}")
    @MethodSource("imports")
    void signedParentProcessesItsIndependentEncryptedAttachment(final Route route, final boolean ordinarySibling) throws Exception {
        final Fixture fixture = fixture(ordinarySibling);
        assertThat(SmimeCryptoSupport.checkSignature(message(fixture.signed))).isTrue();
        final Email unsigned = read(fixture.unsigned, route, loadPkcs12KeyStore());
        final AttachmentResource expected = openedMessage(unsigned);
        final byte[] expectedBody = decodedMessageBody(expected);
        assertThat(readOpenedContent(expected).getPlainText()).contains("This is an encrypted message");
        final Email imported = read(fixture.signed, route, loadPkcs12KeyStore());
        assertParent(imported, fixture.signed);
        assertOriginalForRoute(imported, fixture.signed, route);
        assertThat(imported.getAttachments()).hasSize(ordinarySibling ? 2 : 1);
        assertThat(imported.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        if (ordinarySibling) assertThat(resource(imported.getAttachments(), NOTE_NAME).readAllData(UTF_8)).isEqualTo("Ordinary sibling");
        assertThat(decodedMessageBody(openedMessage(imported))).containsExactly(expectedBody);
        assertThat(imported.getProblems()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER", "STATIC_EML"})
    void noKeyRetainsTheProtectedChildWithoutChangingTheSignedParent(final Route route) throws Exception {
        final Fixture fixture = fixture(false);
        final Email imported = read(fixture.signed, route, null);
        assertParent(imported, fixture.signed);
        assertThat(imported.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.readAllBytes()).containsExactly(fixture.ciphertext);
            assertThat(resource.getEmail()).isNull();
        });
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.ENCRYPTED);
        assertThat(imported.getInspection().getAttachment(0).getDecryptions()).anySatisfy(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER", "STATIC_EML"})
    void explicitlyWrongPasswordsRemainFailures(final Route route) throws Exception {
        final Fixture fixture = fixture(false);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Pkcs12Config wrongPassword = Pkcs12Config.builder().pkcs12Store(keys.getPkcs12StoreData())
                .storePassword("wrong password").keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();

        assertThatThrownBy(() -> read(fixture.signed, route, wrongPassword)).isInstanceOf(SmimeException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER"})
    void anInvalidExtractableParentSignatureDoesNotGateIndependentDecryption(final Route route) throws Exception {
        final Fixture fixture = fixture(true);
        final String changedBody = BODY.replace("stays", "changed");
        final String source = new String(fixture.signed, ISO_8859_1);
        assertThat(source).contains(BODY);
        final byte[] tampered = source.replace(BODY, changedBody).getBytes(ISO_8859_1);
        final Email imported = read(tampered, route, loadPkcs12KeyStore());
        assertThat(imported.getPlainText()).isEqualTo(changedBody);
        assertThat(imported.getHTMLText()).isEqualTo(HTML);
        assertThat(imported.getCalendarText()).isEqualTo(CALENDAR);
        assertPlainProtection(imported, SignatureStatus.INVALID, EncryptionStatus.NOT_ENCRYPTED);
        assertSignatureObservation(imported, SignatureVerification.INVALID);
        assertOriginalForRoute(imported, tampered, route);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(imported.getProblems()).anyMatch(problem -> problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        assertThat(decodedMessageBody(openedMessage(imported))).containsExactly(
                decodedMessageBody(openedMessage(read(fixture.unsigned, route, loadPkcs12KeyStore()))));
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER"})
    void sameNamedOrdinaryFileAndParentSignatureRemainDistinctAcrossCopies(final Route route) throws Exception {
        final Fixture fixture = fixture(encryptedChild(), SIGNATURE_NAME);
        final Email imported = read(fixture.signed, route, loadPkcs12KeyStore());
        for (final Email value : List.of(imported, factory().emailBuilder().copying(imported).buildEmail(), serializedCopy(imported))) {
            assertParent(value, fixture.signed);
            assertThat(decodedMessageBody(openedMessage(value))).containsExactly(
                    decodedMessageBody(openedMessage(read(fixture.unsigned, route, loadPkcs12KeyStore()))));
            assertSameNamedOccurrences(value, fixture.signature);
        }
    }

    @Test
    void repeatedBuildAndCopiesDoNotPromoteOrDuplicateTheIndependentFile() throws Exception {
        final Fixture fixture = fixture(false);
        final EmailPopulatingBuilder builder = factory().converter().fromEml(new ByteArrayInputStream(fixture.signed))
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        final Email first = builder.buildEmail();
        for (final Email value : List.of(first, builder.buildEmail(), factory().emailBuilder().copying(first).buildEmail(), serializedCopy(first))) {
            assertParent(value, fixture.signed);
            assertThat(value.getAttachments()).hasSize(1);
            assertThat(decodedMessageBody(openedMessage(value))).containsExactly(decodedMessageBody(openedMessage(first)));
            assertThat(value.getAttachments().get(0).getEmail()).isNull();
        }
    }

    @Test
    void singlePartClearContentBecomesAUsableFileWithoutReplacingTheParentBody() throws Exception {
        final MimeBodyPart clear = new MimeBodyPart();
        clear.setText("Clear single-part content has no legacy attachment representation", "UTF-8");
        final Fixture fixture = fixture(encrypted(clear), null);
        final Email imported = read(fixture.signed, Route.FLUENT_EML_LAZY, loadPkcs12KeyStore());
        assertParent(imported, fixture.signed);
        assertThat(imported.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.readAllData(UTF_8)).isEqualTo("Clear single-part content has no legacy attachment representation");
            assertThat(resource.getEmail()).isNull();
        });
        assertThat(imported.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
    }

    @Test
    void malformedEncryptedChildrenKeepReadableNeighborsAndTheirOwnFailure() throws Exception {
        final Fixture fixture = fixture(protectedChild(new byte[] {1, 2, 3}, "enveloped-data"), NOTE_NAME);
        final Email imported = read(fixture.signed, Route.FLUENT_MIME_EAGER, loadPkcs12KeyStore());
        assertParent(imported, fixture.signed);
        assertThat(imported.getAttachments()).hasSize(2);
        assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(1, 2, 3);
        assertThat(resource(imported.getAttachments(), NOTE_NAME).readAllData(UTF_8)).isEqualTo("Ordinary sibling");
        assertThat(imported.getInspection().getAttachment(0).getDecryptions()).anySatisfy(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertThat(imported.getProblems()).anyMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER"})
    void optionalMalformedSignedChildrenKeepReadableNeighborsAndTheirOwnProblem(final Route route) throws Exception {
        final Fixture fixture = fixture(protectedChild("not CMS".getBytes(UTF_8), "signed-data"), NOTE_NAME);

        final Email imported = read(fixture.signed, route, null);

        assertParent(imported, fixture.signed);
        assertThat(resource(imported.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(resource(imported.getAttachments(), NOTE_NAME).readAllData(UTF_8)).isEqualTo("Ordinary sibling");
        assertThat(imported.getProblems()).filteredOn(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED)
                .singleElement().satisfies(problem -> {
                    assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
                    assertThat(problem.getSources()).singleElement().satisfies(source -> {
                        assertThat(source).isInstanceOf(EmailSourcePart.class);
                        final EmailSourcePart child = (EmailSourcePart) source;
                        assertThat(child.getOrigin().getReference()).isEqualTo(problem.getOrigin().getReference());
                        assertThat(child.getHeaders()).anySatisfy(header -> {
                            assertThat(header.getName()).isEqualToIgnoringCase("Content-Disposition");
                            assertThat(header.getRawValue()).contains(PROTECTED_NAME);
                        });
                    });
                });
        final Email copy = serializedCopy(factory().emailBuilder().copying(imported).fixingMessageId("<copy@example.test>").buildEmail());
        assertThat(copy.getProblems()).hasSameSizeAs(imported.getProblems());
        assertThat(copy.getProblems().get(0).getOrigin().getReference())
                .isEqualTo(imported.getProblems().get(0).getOrigin().getReference());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void opaqueSignedAndEncryptedParentsAlsoProcessTheIndependentFile(final boolean opaqueSigned) throws Exception {
        final Fixture fixture = fixture(false);
        final MimeBodyPart entity = new MimeBodyPart();
        entity.setContent((MimeMultipart) message(fixture.unsigned).getContent());
        final MimeBodyPart protectedParent = opaqueSigned ? signer().generateEncapsulated(entity)
                : SmimeCryptoSupport.encrypt(entity, certificate(),
                        SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final MimeMessage outer = envelope(new MimeMultipart());
        outer.setDataHandler(protectedParent.getDataHandler());
        outer.setHeader("Content-Type", protectedParent.getContentType());
        outer.saveChanges();
        final byte[] source = bytes(outer);
        final Email imported = read(source, Route.FLUENT_EML_LAZY, loadPkcs12KeyStore());
        assertOriginal(imported, source);
        assertThat(imported.getPlainText()).isEqualTo(BODY);
        assertThat(imported.getHTMLText()).isEqualTo(HTML);
        assertThat(imported.getCalendarText()).isEqualTo(CALENDAR);
        assertPlainProtection(imported, opaqueSigned ? SignatureStatus.VERIFIED : SignatureStatus.UNSIGNED,
                opaqueSigned ? EncryptionStatus.NOT_ENCRYPTED : EncryptionStatus.DECRYPTED);
        assertThat(imported.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(readOpenedContent(openedMessage(imported)).getPlainText()).contains("This is an encrypted message");
    }

    static Stream<Arguments> protectedRoots() {
        return Stream.of(Route.FLUENT_MIME_LAZY, Route.FLUENT_EML_EAGER)
                .flatMap(route -> Stream.of(false, true).map(encrypted -> Arguments.of(route, encrypted)));
    }

    @ParameterizedTest(name = "{0}, encrypted root={1}")
    @MethodSource("protectedRoots")
    void openingDetachedSignatureRecursivelyProcessesItsProtectedRoot(final Route route, final boolean encryptedRoot) throws Exception {
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Another protected root is not an independent attachment", "UTF-8");
        final MimeBodyPart file = new MimeBodyPart();
        file.setText("Ordinary file inside the unopened root", "UTF-8");
        file.setFileName("inside.txt");
        final MimeMultipart clear = new MimeMultipart("mixed");
        clear.addBodyPart(text);
        clear.addBodyPart(file);
        final MimeBodyPart clearEntity = new MimeBodyPart();
        clearEntity.setContent(clear);
        final MimeBodyPart protectedEntity = encryptedRoot
                ? SmimeCryptoSupport.encrypt(clearEntity, certificate(), SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER)
                : signer().generateEncapsulated(clearEntity);
        protectedEntity.setFileName("protected-root.p7m");
        final MimeMultipart detached = signer().generate(protectedEntity);
        ((MimeBodyPart) detached.getBodyPart(1)).setFileName(SIGNATURE_NAME);
        final byte[] wire = bytes(envelope(detached));
        assertThat(SmimeCryptoSupport.checkSignature(message(wire))).isTrue();
        final Email imported = read(wire, route, loadPkcs12KeyStore());
        assertPlainProtection(imported, SignatureStatus.VERIFIED,
                encryptedRoot ? EncryptionStatus.DECRYPTED : EncryptionStatus.NOT_ENCRYPTED);
        assertThat(imported.getPlainText()).isEqualTo("Another protected root is not an independent attachment");
        assertThat(imported.getInspection().getSmime().getSignatures()).hasSize(encryptedRoot ? 1 : 2)
                .allSatisfy(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(imported.getAttachments()).extracting("name").containsExactly("inside.txt");
        assertThat(imported.getAttachments().get(0).readAllData(UTF_8)).isEqualTo("Ordinary file inside the unopened root");
        assertOriginalForRoute(imported, wire, route);
    }

    private static void assertSameNamedOccurrences(final Email email, final byte[] signature) throws Exception {
        final List<AttachmentResource> matching = email.getAttachments().stream().filter(resource -> SIGNATURE_NAME.equals(resource.getName()))
                .collect(Collectors.toList());
        assertThat(matching).singleElement().satisfies(resource -> assertThat(resource.readAllData(UTF_8)).isEqualTo("Ordinary sibling"));
        assertFileOccurrenceDistinctFromEnvelope(email, 1, SIGNATURE_NAME);
        if (email.getInspection().getOriginalMessage() != null) {
            final MimeMessage original = message(email.getInspection().getOriginalMessage().readAllBytes());
            try (InputStream input = ((MimeMultipart) original.getContent()).getBodyPart(1).getInputStream()) {
                assertThat(input.readAllBytes()).containsExactly(signature);
            }
        }
    }

    private static void assertParent(final Email email, final byte[] original) {
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
        assertThat(email.getCalendarText()).isEqualTo(CALENDAR);
        assertThat(email.getHeaders()).containsEntry("X-Parent", List.of("retained"));
        assertPlainProtection(email, SignatureStatus.VERIFIED, EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getInspection().getHTMLText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getInspection().getCalendar().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        if (email.getInspection().getOriginalMessage() != null) assertOriginal(email, original);
    }

    private static void assertOriginalForRoute(Email email, byte[] original, Route route) {
        if (route.name().contains("EML")) assertOriginal(email, original);
        else assertThat(email.getInspection().getOriginalMessage()).isNull();
    }

    private static AttachmentResource openedMessage(final Email email) {
        assertThat(email.getAttachments()).isNotEmpty();
        assertThat(email.getAttachments().get(0).getEmail()).isNull();
        assertThat(email.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        return email.getAttachments().get(0);
    }

    private static byte[] decodedMessageBody(final AttachmentResource resource) { return resource.readAllBytes(); }

    private static Email readOpenedContent(AttachmentResource resource) {
        final MimeMessage adapter = new MimeMessage(Session.getInstance(new Properties()));
        try {
            adapter.setDataHandler(new DataHandler(resource.getDataSource()));
            adapter.setHeader("Content-Type", resource.getDataSource().getContentType());
        } catch (jakarta.mail.MessagingException failure) { throw new IllegalStateException(failure); }
        return factory().converter().fromMimeMessage(adapter).toEmail();
    }

    private static AttachmentResource resource(final List<AttachmentResource> resources, final String name) {
        final List<AttachmentResource> matching = resources.stream().filter(resource -> name.equals(resource.getName()))
                .collect(Collectors.toList());
        assertThat(matching).as(name).hasSize(1);
        return matching.get(0);
    }

    private static Email read(final byte[] bytes, final Route route, final Pkcs12Config keys) throws Exception {
        switch (route) {
            case FLUENT_MIME_EAGER:
            case FLUENT_MIME_LAZY:
                return keys == null
                        ? factory().converter().fromMimeMessage(message(bytes)).withFetchAttachmentData(route == Route.FLUENT_MIME_EAGER).toEmail()
                        : factory().converter().fromMimeMessage(message(bytes))
                                .withFetchAttachmentData(route == Route.FLUENT_MIME_EAGER).withSmimeDecryption(keys).toEmail();
            case FLUENT_EML_EAGER:
            case FLUENT_EML_LAZY:
                return keys == null
                        ? factory().converter().fromEml(new ByteArrayInputStream(bytes)).withFetchAttachmentData(route == Route.FLUENT_EML_EAGER).toEmail()
                        : factory().converter().fromEml(new ByteArrayInputStream(bytes))
                                .withFetchAttachmentData(route == Route.FLUENT_EML_EAGER).withSmimeDecryption(keys).toEmail();
            case STATIC_MIME_EAGER:
            case STATIC_MIME_LAZY:
                return EmailConverter.mimeMessageToEmail(message(bytes), keys, route == Route.STATIC_MIME_EAGER);
            case STATIC_EML:
                return EmailConverter.emlToEmail(new ByteArrayInputStream(bytes), keys);
            default:
                throw new AssertionError(route);
        }
    }

    private static Fixture fixture(final boolean ordinarySibling) throws Exception {
        return fixture(encryptedChild(), ordinarySibling ? NOTE_NAME : null);
    }

    private static MimeBodyPart encryptedChild() throws Exception {
        final MimeBodyPart encrypted = new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(resourcePath(
                "pkcs12/test messages/S_MIME test message encrypted.eml"))));
        encrypted.setHeader("Content-Type", "application/pkcs7-mime; smime-type=enveloped-data; name=" + PROTECTED_NAME);
        encrypted.setFileName(PROTECTED_NAME);
        return encrypted;
    }

    private static MimeBodyPart protectedChild(final byte[] payload, final String smimeType) throws Exception {
        final MimeBodyPart child = new MimeBodyPart();
        child.setDataHandler(new DataHandler(new ByteArrayDataSource(payload,
                "application/pkcs7-mime; smime-type=" + smimeType + "; name=" + PROTECTED_NAME)));
        child.setFileName(PROTECTED_NAME);
        return child;
    }

    private static MimeBodyPart encrypted(final MimeBodyPart clear) throws Exception {
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(clear, certificate(),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        encrypted.setFileName(PROTECTED_NAME);
        return encrypted;
    }

    private static Fixture fixture(final MimeBodyPart encrypted, final String ordinarySiblingName) throws Exception {
        final byte[] ciphertext;
        try (InputStream input = encrypted.getInputStream()) { ciphertext = input.readAllBytes(); }

        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText(BODY, "UTF-8");
        final MimeBodyPart html = new MimeBodyPart();
        html.setContent(HTML, "text/html; charset=UTF-8");
        final MimeMultipart alternatives = new MimeMultipart("alternative");
        alternatives.addBodyPart(plain);
        alternatives.addBodyPart(html);
        final MimeBodyPart text = new MimeBodyPart();
        text.setContent(alternatives);
        final MimeBodyPart calendar = new MimeBodyPart();
        calendar.setDataHandler(new DataHandler(new ByteArrayDataSource(CALENDAR.getBytes(UTF_8),
                "text/calendar; method=REQUEST; charset=UTF-8")));
        final MimeMultipart clear = new MimeMultipart("mixed");
        clear.addBodyPart(text);
        clear.addBodyPart(calendar);
        clear.addBodyPart(encrypted);
        if (ordinarySiblingName != null) {
            final MimeBodyPart note = new MimeBodyPart();
            note.setText("Ordinary sibling", "UTF-8");
            note.setFileName(ordinarySiblingName);
            clear.addBodyPart(note);
        }
        final MimeMessage unsigned = envelope(clear);
        final byte[] unsignedBytes = bytes(unsigned);
        final MimeBodyPart entity = new MimeBodyPart();
        entity.setContent((MimeMultipart) message(unsignedBytes).getContent());
        final MimeMultipart signed = signer().generate(entity);
        ((MimeBodyPart) signed.getBodyPart(1)).setFileName(SIGNATURE_NAME);
        final byte[] signedBytes = bytes(envelope(signed));
        final byte[] signature;
        try (InputStream input = ((MimeMultipart) message(signedBytes).getContent()).getBodyPart(1).getInputStream()) {
            signature = input.readAllBytes();
        }
        return new Fixture(unsignedBytes, signedBytes, ciphertext, signature);
    }

    private static SMIMESignedGenerator signer() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        return generator;
    }

    private static X509Certificate certificate() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return (X509Certificate) store.getCertificate(keys.getKeyAlias());
    }

    private static MimeMessage envelope(final MimeMultipart content) throws Exception {
        final Properties properties = new Properties();
        // Message-ID generation is not part of the signed-parent checks.
        properties.setProperty("mail.host", "localhost");
        final MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setFrom("sender@example.org");
        message.setRecipients(Message.RecipientType.TO, "reader@example.org");
        message.setSubject("Signed parent with independently encrypted attachment", "UTF-8");
        message.setHeader("X-Parent", "retained");
        message.setContent(content);
        message.saveChanges();
        return message;
    }

    private static MimeMessage message(final byte[] bytes) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static Path resourcePath(final String relative) {
        return Path.of(determineResourceFolder("simple-java-mail"), "test/resources", relative);
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static final class Fixture {
        private final byte[] unsigned;
        private final byte[] signed;
        private final byte[] ciphertext;
        private final byte[] signature;

        private Fixture(final byte[] unsigned, final byte[] signed, final byte[] ciphertext, final byte[] signature) {
            this.unsigned = unsigned;
            this.signed = signed;
            this.ciphertext = ciphertext;
            this.signature = signature;
        }
    }
}
