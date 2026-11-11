package org.simplejavamail.openpgp;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.simplejavamail.MailException;
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
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

/** Independent S/MIME files retain their own processing after an OpenPGP parent is opened. */
@Timeout(30)
class OpenPgpIndependentSmimeAttachmentTest {
    private static final String BODY = "OpenPGP parent body";
    private static final String HTML = "<p>OpenPGP parent body</p>";
    private static final String CALENDAR = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n";
    private static final String CHILD = "independent.p7m";
    private static final String NOTE = "note.txt";

    private enum Parent { SIGNED, SIGNED_ENCRYPTED }
    private enum Route { MIME_EAGER, MIME_LAZY, EML_EAGER, EML_LAZY }

    @BeforeAll
    static void keys() throws Exception {
        OpenPgpSignatureFixtures.initialize();
    }

    static Stream<Arguments> imports() {
        return Stream.of(Route.values()).flatMap(route -> Stream.of(Parent.values()).map(parent -> Arguments.of(route, parent)));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("imports")
    void openedParentsProcessTheirIndependentEncryptedFile(final Route route, final Parent parent) throws Exception {
        final byte[] ciphertext = encryptedChild();
        final byte[] wire = protectedMail(parent, ciphertext, "enveloped-data", true);
        final Email unsigned = readUnsigned(ciphertext);
        final byte[] expected = opened(unsigned).readAllBytes();
        assertThat(unsigned.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(expected).isNotEmpty().isNotEqualTo(ciphertext);

        final Email imported = read(wire, route, receiving(), loadPkcs12KeyStore());

        assertParent(imported, wire, parent, route == Route.EML_EAGER || route == Route.EML_LAZY);
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly(CHILD, NOTE);
        final AttachmentResource selected = imported.getAttachments().get(0);
        assertThat(selected.readAllBytes()).containsExactly(expected);
        assertThat(imported.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(ciphertext);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertIndependentFileDecryption(imported, DecryptionStatus.DECRYPTED);
        assertThat(resource(imported.getAttachments(), NOTE).readAllData(UTF_8)).isEqualTo("Readable neighbor");
        assertThat(selected.getEmail()).isNull();
        assertThat(imported.getProblems()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Parent.class)
    void absentSmimeKeysRetainTheEncryptedFile(final Parent parent) throws Exception {
        final byte[] ciphertext = encryptedChild();
        final byte[] wire = protectedMail(parent, ciphertext, "enveloped-data", true);

        final Email imported = read(wire, Route.EML_EAGER, receiving(), null);

        assertParent(imported, wire, parent);
        assertThat(resource(imported.getAttachments(), CHILD).readAllBytes()).containsExactly(ciphertext);
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.ENCRYPTED);
        assertThat(imported.getInspection().getAttachment(0).getDecryptions()).anySatisfy(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
    }

    @ParameterizedTest
    @EnumSource(Parent.class)
    void explicitlyWrongSmimePasswordsStillFail(final Parent parent) throws Exception {
        final byte[] wire = protectedMail(parent, encryptedChild(), "enveloped-data", true);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Pkcs12Config wrong = Pkcs12Config.builder().pkcs12Store(keys.getPkcs12StoreData()).storePassword("wrong")
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();

        assertThatThrownBy(() -> read(wire, Route.MIME_EAGER, receiving(), wrong)).isInstanceOf(MailException.class)
                .hasMessageContaining("PKCS12");
    }

    @ParameterizedTest
    @EnumSource(Parent.class)
    void malformedOptionalSignedFilesKeepTheirOwnProblemAndReadableNeighbors(final Parent parent) throws Exception {
        final byte[] original = "not a CMS signature".getBytes(UTF_8);
        final byte[] wire = protectedMail(parent, original, "signed-data", true);

        final Email imported = read(wire, Route.EML_EAGER, receiving(), null);

        assertParent(imported, wire, parent);
        assertThat(resource(imported.getAttachments(), CHILD).readAllBytes()).containsExactly(original);
        assertThat(resource(imported.getAttachments(), NOTE).readAllData(UTF_8)).isEqualTo("Readable neighbor");
        assertThat(imported.getProblems()).filteredOn(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED)
                .singleElement().satisfies(problem -> {
                    assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
                    assertThat(problem.getSources()).singleElement().satisfies(source -> {
                        assertThat(source).isInstanceOf(EmailSourcePart.class);
                        final EmailSourcePart file = (EmailSourcePart) source;
                        assertThat(file.getOrigin().getReference()).isEqualTo(problem.getOrigin().getReference());
                        assertThat(file.getHeaders()).anySatisfy(header -> {
                            assertThat(header.getName()).isEqualToIgnoringCase("Content-Disposition");
                            assertThat(header.getRawValue()).contains(CHILD);
                        });
                    });
                });
    }

    @Test
    void aSoleOpenedFileDoesNotReplaceTheParentAndSurvivesCopying() throws Exception {
        final byte[] wire = protectedMail(Parent.SIGNED, encryptedChild(), "enveloped-data", false);
        final EmailPopulatingBuilder builder = mail().converter().fromEml(wire).withOpenPgpReceiveConfig(receiving())
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        final Email first = builder.buildEmail();

        for (Email value : List.of(first, builder.buildEmail(), mail().emailBuilder().copying(first).buildEmail(), serializedCopy(first))) {
            assertParent(value, wire, Parent.SIGNED);
            assertThat(opened(value).readAllBytes()).containsExactly(opened(first).readAllBytes());
            assertThat(value.getAttachments()).hasSameSizeAs(first.getAttachments());
            assertThat(opened(value).getEmail()).isNull();
        }
    }

    @Test
    void anInvalidButReadableOpenPgpSignatureDoesNotGateIndependentDecryption() throws Exception {
        final byte[] original = protectedMail(Parent.SIGNED, encryptedChild(), "enveloped-data", true);
        final String source = new String(original, ISO_8859_1);
        assertThat(source).contains(BODY);
        final String changed = "Changed OpenPGP parent body";
        final byte[] tampered = source.replace(BODY, changed).getBytes(ISO_8859_1);

        final Email imported = read(tampered, Route.EML_EAGER, receiving(), loadPkcs12KeyStore());

        assertThat(imported.getPlainText()).isEqualTo(changed);
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
        assertThat(imported.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(tampered);
        assertThat(imported.getProblems()).anyMatch(problem -> problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        assertThat(opened(imported).readAllBytes()).isNotEmpty();
    }

    @Test
    void unopenedOpenPgpCiphertextDoesNotAcquireASmimeChildScan() throws Exception {
        final byte[] wire = protectedMail(Parent.SIGNED_ENCRYPTED, encryptedChild(), "enveloped-data", true);
        final OpenPgpReceiveConfig noDecryptionKey = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(OpenPgpSignatureFixtures.first.publicRing).build();

        final Email imported = read(wire, Route.EML_LAZY, noDecryptionKey, loadPkcs12KeyStore());

        assertThat(imported.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(wire);
        assertThat(imported.getInspection().getSmime().getDecryptions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Parent.class)
    void unavailableSmimeSupportDoesNotPreventOpeningTheOpenPgpParent(final Parent parent) throws Exception {
        final byte[] ciphertext = encryptedChild();
        final byte[] wire = protectedMail(parent, ciphertext, "enveloped-data", true);

        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(false);
            for (Route route : List.of(Route.EML_EAGER, Route.EML_LAZY)) {
                final Email imported = read(wire, route, receiving(), null);
                assertParent(imported, wire, parent);
                assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly(CHILD, NOTE);
                assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(ciphertext);
                assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.ENCRYPTED);
                if (route == Route.EML_EAGER) {
                    assertThat(imported.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(ciphertext);
                    assertIndependentFileDecryption(imported, DecryptionStatus.UNAVAILABLE);
                } else {
                    assertThat(imported.getInspection().getOriginalAttachment(0)).isNull();
                    assertIndependentFileDecryption(imported, DecryptionStatus.DEFERRED);
                }
            }
        }
    }

    @Test
    void aFurtherSmimeEnvelopeAtTheEffectiveRootIsTheSameLogicalMessagesContent() throws Exception {
        final byte[] protectedRoot = Files.readAllBytes(encryptedPath());
        final byte[] wire = OpenPgpSignatureFixtures.detached(protectedRoot, -1, OpenPgpSignatureFixtures.first);
        final Email imported = read(wire, Route.EML_EAGER, receiving(), loadPkcs12KeyStore());
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(imported.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(imported.getPlainText()).contains("This is an encrypted message");
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(wire);
    }

    private static EmailPopulatingBuilder composed(final byte[] content, final String smimeType, final boolean note) {
        final EmailPopulatingBuilder builder = mail().emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withSubject("OpenPGP with an independent S/MIME file").withPlainText(BODY).withHTMLText(HTML)
                .withCalendarText(CalendarMethod.REQUEST, CALENDAR).withHeader("X-Parent", "retained")
                .withAttachment(CHILD, content, "application/pkcs7-mime; smime-type=" + smimeType);
        if (note) builder.withAttachment(NOTE, "Readable neighbor".getBytes(UTF_8), "text/plain");
        return builder;
    }

    private static byte[] protectedMail(final Parent parent, final byte[] content, final String smimeType, final boolean note) {
        final var key = OpenPgpSignatureFixtures.first;
        final EmailPopulatingBuilder builder = composed(content, smimeType, note)
                .signWithOpenPgp(OpenPgpSigningConfig.builder().secretKeyRing(key.secret).passphrase(key.passphrase).build());
        if (parent == Parent.SIGNED_ENCRYPTED) {
            builder.encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(key.publicRing).build());
        }
        return mail().converter().fromEmail(builder.buildEmail()).toEmlBytes();
    }

    private static Email readUnsigned(final byte[] ciphertext) {
        return mail().converter().fromEml(mail().converter().fromEmail(composed(ciphertext, "enveloped-data", true).buildEmail()).toEmlBytes())
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
    }

    private static OpenPgpReceiveConfig receiving() {
        return OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first);
    }

    private static Email read(final byte[] source, final Route route, final OpenPgpReceiveConfig openPgp, final Pkcs12Config smime)
            throws Exception {
        final var input = route == Route.MIME_EAGER || route == Route.MIME_LAZY
                ? mail().converter().fromMimeMessage(message(source)) : mail().converter().fromEml(source);
        input.withOpenPgpReceiveConfig(openPgp).withFetchAttachmentData(route == Route.MIME_EAGER || route == Route.EML_EAGER);
        if (smime != null) input.withSmimeDecryption(smime);
        return input.toEmail();
    }

    private static void assertParent(final Email email, final byte[] original, final Parent parent) {
        assertParent(email, original, parent, true);
    }

    private static void assertParent(final Email email, final byte[] original, final Parent parent, final boolean retainedOriginal) {
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
        assertThat(email.getCalendarText()).isEqualTo(CALENDAR);
        assertThat(email.getHeaders()).containsEntry("X-Parent", List.of("retained"));
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(parent == Parent.SIGNED
                ? EncryptionStatus.NOT_ENCRYPTED : EncryptionStatus.DECRYPTED);
        assertThat(email.getInspection().getPlainText().getDecryptions()).noneMatch(attempt -> attempt.getFamily() == ProtectionFamily.SMIME);
        assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
        if (retainedOriginal) {
            assertThat(email.getInspection().getOriginalMessage()).isNotNull();
            assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(original);
        } else {
            assertThat(email.getInspection().getOriginalMessage()).isNull();
        }
    }

    private static void assertIndependentFileDecryption(final Email email, final DecryptionStatus status) {
        assertThat(email.getInspection().getAttachment(0).getDecryptions())
                .filteredOn(attempt -> attempt.getFamily() == ProtectionFamily.SMIME)
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(status);
                    assertThat(attempt.getSources()).singleElement().satisfies(source -> {
                        assertThat(source).isInstanceOf(EmailSourcePart.class);
                        assertThat(((EmailSourcePart) source).getHeaders()).anySatisfy(header -> {
                            assertThat(header.getName()).isEqualToIgnoringCase("Content-Disposition");
                            assertThat(header.getRawValue()).contains(CHILD);
                        });
                    });
                    assertThat(email.getInspection().getSmime().getDecryptions()).singleElement()
                            .satisfies(historical -> assertThat(historical.getReference()).isEqualTo(attempt.getReference()));
                });
    }

    private static AttachmentResource opened(final Email email) {
        return resource(email.getAttachments(), CHILD);
    }

    private static AttachmentResource resource(final List<AttachmentResource> resources, final String name) {
        return resources.stream().filter(resource -> name.equals(resource.getName())).findFirst().orElseThrow();
    }

    private static byte[] encryptedChild() throws Exception {
        final MimeBodyPart encrypted = new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(encryptedPath())));
        try (InputStream content = encrypted.getInputStream()) { return content.readAllBytes(); }
    }

    private static Path encryptedPath() {
        return Path.of(determineResourceFolder("simple-java-mail"), "test/resources/pkcs12/test messages/S_MIME test message encrypted.eml");
    }

    private static MimeMessage message(final byte[] source) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
    }

    private static byte[] decodedBody(final byte[] source) throws Exception {
        try (InputStream content = message(source).getInputStream()) { return content.readAllBytes(); }
    }

    private static Email serializedCopy(final Email value) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(output)) { stream.writeObject(value); }
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            return (Email) stream.readObject();
        }
    }

    private static SimpleJavaMail mail() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }
}
