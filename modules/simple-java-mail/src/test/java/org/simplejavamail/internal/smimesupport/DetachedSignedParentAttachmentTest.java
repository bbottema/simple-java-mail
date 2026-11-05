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
import org.simplejavamail.api.email.OriginalSmimeDetails;
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
        assertThat(SmimeCryptoSupport.checkSignature(message(fixture.signed))).as("received detached signature is valid").isTrue();
        final Email unsigned = read(fixture.unsigned, route, loadPkcs12KeyStore());
        final AttachmentResource expected = openedMessage(unsigned);
        final byte[] expectedBody = decodedMessageBody(expected);
        assertThat(factory().converter().fromEml(new ByteArrayInputStream(expected.readAllBytes())).toEmail().getPlainText())
                .contains("This is an encrypted message");

        final Email imported = read(fixture.signed, route, loadPkcs12KeyStore());

        assertParent(imported, fixture.signed);
        assertThat(resource(imported.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(resource(imported.getAttachments(), SIGNATURE_NAME).readAllBytes()).containsExactly(fixture.signature);
        if (ordinarySibling) {
            assertThat(resource(imported.getAttachments(), NOTE_NAME).readAllData(UTF_8)).isEqualTo("Ordinary sibling");
        }
        final AttachmentResource opened = openedMessage(imported);
        assertThat(decodedMessageBody(opened)).containsExactly(expectedBody);
        assertThat(resource(imported.getDecryptedAttachments(), SIGNATURE_NAME).readAllBytes()).containsExactly(fixture.signature);
        assertThat(imported.getSmimeSignedEmail()).as("an independent attachment does not become the parent body").isNull();
        assertThat(imported.getProblems()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER", "STATIC_EML"})
    void noKeyRetainsTheProtectedChildWithoutChangingTheSignedParent(final Route route) throws Exception {
        final Fixture fixture = fixture(false);

        final Email imported = read(fixture.signed, route, null);

        assertParent(imported, fixture.signed);
        assertThat(resource(imported.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(resource(imported.getDecryptedAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(imported.getDecryptedAttachments()).noneMatch(resource ->
                "message/rfc822".equalsIgnoreCase(resource.getDataSource().getContentType()));
        assertThat(imported.getSmimeSignedEmail()).isNull();
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
        assertThat(imported.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
        assertThat(imported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.INVALID);
        assertThat(imported.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(imported.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(tampered);
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
            assertSameNamedOccurrences(value.getAttachments(), fixture.signature);
            assertSameNamedOccurrences(value.getDecryptedAttachments(), fixture.signature);
            assertThat(value.getSmimeSignedEmail()).isNull();
        }
    }

    @Test
    void repeatedBuildAndExplicitSeparateViewsDoNotPromoteOrDuplicateTheChild() throws Exception {
        final Fixture fixture = fixture(false);
        final EmailPopulatingBuilder builder = factory().converter().fromEml(new ByteArrayInputStream(fixture.signed))
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder().notMergingSingleSMIMESignedAttachment();
        final Email first = builder.buildEmail();
        final int processedCount = first.getDecryptedAttachments().size();

        for (final Email value : List.of(first, builder.buildEmail(), factory().emailBuilder().copying(first)
                .clearSMIMESignedAttachmentMergingBehavior().buildEmail(), serializedCopy(first))) {
            assertParent(value, fixture.signed);
            assertThat(value.getDecryptedAttachments()).hasSize(processedCount);
            assertThat(decodedMessageBody(openedMessage(value))).containsExactly(decodedMessageBody(openedMessage(first)));
            assertThat(value.getSmimeSignedEmail()).isNull();
        }
    }

    @Test
    void unsupportedSinglePartClearContentRetainsTheOriginalProtectedResource() throws Exception {
        final MimeBodyPart clear = new MimeBodyPart();
        clear.setText("Clear single-part content has no legacy attachment representation", "UTF-8");
        final Fixture fixture = fixture(encrypted(clear), null);

        final Email imported = read(fixture.signed, Route.FLUENT_EML_LAZY, loadPkcs12KeyStore());

        assertParent(imported, fixture.signed);
        assertThat(resource(imported.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(resource(imported.getDecryptedAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(imported.getSmimeSignedEmail()).isNull();
    }

    @Test
    void malformedEncryptedChildrenKeepTheExplicitDecryptionFailureBoundary() throws Exception {
        final MimeBodyPart broken = protectedChild(new byte[] {1, 2, 3}, "enveloped-data");
        final Fixture fixture = fixture(broken, NOTE_NAME);

        assertThatThrownBy(() -> read(fixture.signed, Route.FLUENT_MIME_EAGER, loadPkcs12KeyStore()))
                .isInstanceOf(SmimeException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FLUENT_MIME_LAZY", "FLUENT_EML_EAGER"})
    void optionalMalformedSignedChildrenKeepReadableNeighborsAndTheirOwnProblem(final Route route) throws Exception {
        final Fixture fixture = fixture(protectedChild("not CMS".getBytes(UTF_8), "signed-data"), NOTE_NAME);

        final Email imported = read(fixture.signed, route, null);

        assertParent(imported, fixture.signed);
        assertThat(resource(imported.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(resource(imported.getDecryptedAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
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
    void opaqueSignedAndEncryptedParentsKeepTheirExistingBoundedInnerView(final boolean opaqueSigned) throws Exception {
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

        assertThat(imported.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(imported.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(opaqueSigned
                ? OriginalSmimeDetails.SmimeMode.SIGNED : OriginalSmimeDetails.SmimeMode.ENCRYPTED);
        final Email inner = imported.getSmimeSignedEmail();
        assertThat(inner).isNotNull();
        assertThat(inner.getPlainText()).isEqualTo(BODY);
        assertThat(resource(inner.getAttachments(), PROTECTED_NAME).readAllBytes()).containsExactly(fixture.ciphertext);
        assertThat(inner.getDecryptedAttachments()).noneMatch(resource ->
                "message/rfc822".equalsIgnoreCase(resource.getDataSource().getContentType()));
    }

    static Stream<Arguments> protectedRoots() {
        return Stream.of(Route.FLUENT_MIME_LAZY, Route.FLUENT_EML_EAGER)
                .flatMap(route -> Stream.of(false, true).map(encrypted -> Arguments.of(route, encrypted)));
    }

    @ParameterizedTest(name = "{0}, encrypted root={1}")
    @MethodSource("protectedRoots")
    void openingDetachedSignatureDoesNotOpenTheProtectedEntityAtItsRoot(final Route route, final boolean encryptedRoot)
            throws Exception {
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
        final MimeBodyPart generatedProtected = encryptedRoot
                ? SmimeCryptoSupport.encrypt(clearEntity, certificate(),
                        SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER)
                : signer().generateEncapsulated(clearEntity);
        generatedProtected.setFileName("protected-root.p7m");
        final ByteArrayOutputStream protectedSnapshot = new ByteArrayOutputStream();
        generatedProtected.writeTo(protectedSnapshot);
        final MimeBodyPart protectedEntity = new MimeBodyPart(new ByteArrayInputStream(protectedSnapshot.toByteArray()));
        // The signed content itself is protected, without any surrounding clear multipart attachment container.
        final MimeMultipart detached = signer().generate(protectedEntity);
        ((MimeBodyPart) detached.getBodyPart(1)).setFileName(SIGNATURE_NAME);
        final byte[] wire = bytes(envelope(detached));
        final MimeBodyPart receivedProtected = (MimeBodyPart) ((MimeMultipart) message(wire).getContent()).getBodyPart(0);
        final byte[] protectedBytes;
        try (InputStream input = receivedProtected.getInputStream()) { protectedBytes = input.readAllBytes(); }
        assertThat(SmimeCryptoSupport.checkSignature(message(wire))).isTrue();

        final Email imported = read(wire, route, loadPkcs12KeyStore());

        assertThat(imported.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
        assertThat(imported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(imported.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(imported.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(wire);
        assertThat(imported.getPlainText()).isNull();
        assertThat(imported.getHTMLText()).isNull();
        assertThat(imported.getSmimeSignedEmail()).isNull();
        for (final List<AttachmentResource> resources : List.of(imported.getAttachments(), imported.getDecryptedAttachments())) {
            final List<AttachmentResource> protectedRoots = resources.stream().filter(resource ->
                    resource.getDataSource().getContentType().startsWith("application/pkcs7-mime")).collect(Collectors.toList());
            assertThat(protectedRoots).hasSize(1);
            assertThat(protectedRoots.get(0).readAllBytes()).containsExactly(protectedBytes);
        }
        assertThat(imported.getDecryptedAttachments()).noneMatch(resource ->
                "message/rfc822".equalsIgnoreCase(resource.getDataSource().getContentType()));
    }

    private static void assertSameNamedOccurrences(final List<AttachmentResource> resources, final byte[] signature) throws Exception {
        final List<AttachmentResource> matching = resources.stream().filter(resource -> SIGNATURE_NAME.equals(resource.getName()))
                .collect(Collectors.toList());
        assertThat(matching).hasSize(2);
        final List<byte[]> contents = new java.util.ArrayList<>();
        for (AttachmentResource resource : matching) { contents.add(resource.readAllBytes()); }
        assertThat(contents).anySatisfy(content -> assertThat(content).containsExactly(signature))
                .anySatisfy(content -> assertThat(content).containsExactly("Ordinary sibling".getBytes(UTF_8)));
    }

    private static void assertParent(final Email email, final byte[] original) {
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
        assertThat(email.getCalendarText()).isEqualTo(CALENDAR);
        assertThat(email.getHeaders()).containsEntry("X-Parent", List.of("retained"));
        assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(original);
    }

    private static AttachmentResource openedMessage(final Email email) {
        final List<AttachmentResource> opened = email.getDecryptedAttachments().stream()
                .filter(resource -> "message/rfc822".equalsIgnoreCase(resource.getDataSource().getContentType()))
                .collect(Collectors.toList());
        assertThat(opened).as("independent encrypted child has the same opened representation as under an unsigned parent").hasSize(1);
        return opened.get(0);
    }

    private static byte[] decodedMessageBody(final AttachmentResource resource) throws Exception {
        try (InputStream content = message(resource.readAllBytes()).getInputStream()) {
            return content.readAllBytes();
        }
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
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
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
