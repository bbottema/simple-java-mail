package org.simplejavamail.email.internal;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class ExactEmailProtectionModuleAbsenceTest {

    private static final String SMIME_IMPLEMENTATION = "org.simplejavamail.internal.smimesupport.SMIMESupport";
    private static final String OPENPGP_IMPLEMENTATION = "org.simplejavamail.internal.openpgpsupport.OpenPgpSupport";

    @TestFactory
    Stream<DynamicTest> exactInspectionDoesNotRequireEitherOptionalProtectionModule() {
        return Arrays.stream(RuntimeProfile.values()).map(profile -> DynamicTest.dynamicTest(profile.name(), () -> runWithoutModules(profile)));
    }

    private static void runWithoutModules(final RuntimeProfile profile) throws Exception {
        final String original = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final List<Path> removedArtifacts = new ArrayList<>();
        if (!profile.smimePresent) removedArtifacts.add(implementationArtifact(SMIME_IMPLEMENTATION));
        if (!profile.openPgpPresent) removedArtifacts.add(implementationArtifact(OPENPGP_IMPLEMENTATION));
        final String classpath = Arrays.stream(original.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !removedArtifacts.contains(Path.of(entry).toAbsolutePath().normalize()))
                .collect(Collectors.joining(File.pathSeparator));
        assertThat(classpath).as("The absent implementation artifacts are removed from the child classpath").isNotEqualTo(original);

        final Path outputDirectory = Path.of("target", "exact-protection-module-absence");
        Files.createDirectories(outputDirectory);
        final Path output = outputDirectory.resolve(profile.name() + ".log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Probe.class.getName(), profile.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            final boolean finished = child.waitFor(45, SECONDS);
            assertThat(finished).as("Child completed: %s", Files.readString(output)).isTrue();
            final String diagnostics = Files.readString(output);
            assertThat(child.exitValue()).as(diagnostics).isZero();
            assertThat(diagnostics).contains("MODULES smime=" + profile.smimePresent + " openpgp=" + profile.openPgpPresent)
                    .contains("PASSED " + profile.name() + " specimens=" + specimens(profile).size());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, SECONDS);
            }
        }
    }

    private static Path implementationArtifact(final String implementation) throws Exception {
        return Path.of(Class.forName(implementation, false, ExactEmailProtectionModuleAbsenceTest.class.getClassLoader())
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
    }

    public static final class Probe {
        public static void main(final String[] args) throws Exception {
            final RuntimeProfile profile = RuntimeProfile.valueOf(args[0]);
            assertModulePresence(SMIME_IMPLEMENTATION, profile.smimePresent);
            assertModulePresence(OPENPGP_IMPLEMENTATION, profile.openPgpPresent);
            require(ModuleLoader.smimeModuleAvailable() == profile.smimePresent, "S/MIME availability must match actual classpath");
            require(ModuleLoader.openPgpModuleAvailable() == profile.openPgpPresent, "OpenPGP availability must match actual classpath");
            // These shared dependencies remain present even when both SJM feature implementations are absent.
            Class.forName("org.bouncycastle.cert.X509CertificateHolder", false, Probe.class.getClassLoader());
            Class.forName("org.bouncycastle.openpgp.PGPException", false, Probe.class.getClassLoader());
            System.out.println("MODULES smime=" + profile.smimePresent + " openpgp=" + profile.openPgpPresent);

            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            assertLimitedRawInputKeepsItsPrefixAndUnknownFacts(mail);
            assertLimitedDiagnosticsDoNotReadOmittedContent(mail, "application/unknown-signature");
            assertBoundedUnreadableSmimeContainerDoesNotReadChildren(mail, "application/unknown-signature");
            if (!profile.smimePresent) {
                assertLimitedDiagnosticsDoNotReadOmittedContent(mail, "application/pkcs7-signature");
                assertBoundedUnreadableSmimeContainerDoesNotReadChildren(mail, "application/pkcs7-signature");
            }
            if (!profile.openPgpPresent) {
                assertLimitedDiagnosticsDoNotReadOmittedContent(mail, "application/pgp-signature");
                assertUnavailableOpenPgpCaptureNeedsACompleteProjection(mail);
            }
            for (Specimen specimen : specimens(profile)) {
                final byte[] source = source(specimen);
                final Email exact = mail.emailBuilder().startingFromExactEml(source)
                        .withEnvelopeRecipients("relay@example.org").buildEmail();
                assertFacts(exact, specimen, source);
                assertRawView(exact, specimen);
                require(Arrays.equals(EmailConverter.emailToEMLByteArray(exact), source), specimen.name + ": exact export changed bytes");

                final Email restored = roundTrip(exact);
                assertFacts(restored, specimen, source);
                assertRawView(restored, specimen);
                require(Arrays.equals(EmailConverter.emailToEMLByteArray(restored), source), specimen.name + ": restored exact export changed bytes");
                System.out.println("CHECKED " + specimen.name);
            }
            System.out.println("PASSED " + profile.name() + " specimens=" + specimens(profile).size());
        }

        private static void assertLimitedRawInputKeepsItsPrefixAndUnknownFacts(final SimpleJavaMail mail) throws Exception {
            final byte[] source = ("From: author@example.org\r\nSubject: limited raw input\r\n\r\n" + "x".repeat(100)).getBytes(US_ASCII);
            final ImportLimitsConfig limits = ImportLimitsConfig.builder().withMaximumInputBytes(16).build();
            assertLimitedRawPrefix(mail.converter().fromEml(new ByteArrayInputStream(source)).withImportLimitsConfig(limits).toEmail(), source);
            assertLimitedRawPrefix(mail.converter().fromOutlookMsg(new ByteArrayInputStream(source)).withImportLimitsConfig(limits).toEmail(), source);
        }

        private static void assertLimitedRawPrefix(final Email imported, final byte[] source) throws Exception {
            require(imported.getPlainText() == null, "An incomplete raw prefix must not become an interpreted body");
            require(imported.getProblems().size() == 1
                    && imported.getProblems().get(0).getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, "The raw stop must remain observable");
            final org.simplejavamail.api.email.EmailSourceContent prefix = imported.getSourceData().getParts().get(0).getContent();
            require(prefix.getSize() == 16 && !prefix.isFullyCaptured(), "The retained prefix must be bounded and incomplete");
            require(Arrays.equals(prefix.readAllBytes(), Arrays.copyOf(source, 16)), "The captured raw prefix must remain exact");
            require(imported.getOriginalSmimeDetails().getSmimeMode() == OriginalSmimeDetails.SmimeMode.UNKNOWN,
                    "A raw stop cannot establish S/MIME mode");
            require(imported.getOriginalSmimeDetails().getVerificationStatus() == OriginalSmimeDetails.VerificationStatus.UNKNOWN
                    && imported.getOriginalSmimeDetails().getDecryptionStatus() == OriginalSmimeDetails.DecryptionStatus.UNKNOWN,
                    "A raw stop cannot establish S/MIME processing facts");
            require(imported.getOriginalOpenPgpDetails().getOpenPgpMode() == OriginalOpenPgpDetails.OpenPgpMode.UNKNOWN,
                    "A raw stop cannot establish OpenPGP mode");
        }

        private static void assertLimitedDiagnosticsDoNotReadOmittedContent(final SimpleJavaMail mail, final String protocol) throws Exception {
            final jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(new java.util.Properties()));
            final jakarta.mail.internet.MimeMultipart parts = new jakarta.mail.internet.MimeMultipart("signed");
            final jakarta.mail.internet.MimeBodyPart text = new jakarta.mail.internet.MimeBodyPart();
            text.setText("Readable limited body");
            parts.addBodyPart(text);
            final jakarta.mail.internet.MimeBodyPart omitted = new jakarta.mail.internet.MimeBodyPart();
            omitted.setDisposition("attachment");
            omitted.setFileName("omitted.bin");
            omitted.setDataHandler(new jakarta.activation.DataHandler(new jakarta.activation.DataSource() {
                @Override public java.io.InputStream getInputStream() { throw new AssertionError("Omitted content was read without its module"); }
                @Override public java.io.OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                @Override public String getContentType() { return "application/octet-stream"; }
                @Override public String getName() { return "omitted.bin"; }
            }));
            parts.addBodyPart(omitted);
            message.setContent(parts);
            message.setHeader("Content-Type", parts.getContentType() + "; protocol=\"" + protocol + "\"");

            final Email imported = mail.converter().fromMimeMessage(message)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();
            require(imported.getPlainText().contains("Readable limited body"), "Limited readable body must remain available");
            require(imported.getAttachments().isEmpty(), "The unadmitted attachment must remain omitted");
            if ("application/pgp-signature".equals(protocol)) {
                require(imported.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null, "Diagnostics must not acquire omitted OpenPGP bytes");
                require(imported.getOriginalOpenPgpDetails().getOpenPgpMode() == OriginalOpenPgpDetails.OpenPgpMode.SIGNED,
                        "The source OpenPGP declaration must remain available");
                require(imported.getOriginalOpenPgpDetails().getSignatureStatus() == OriginalOpenPgpDetails.SignatureStatus.UNKNOWN,
                        "Unavailable OpenPGP verification must remain unknown");
            } else {
                require(imported.getOriginalSmimeDetails().getOriginalProtectedMessage() == null, "Diagnostics must not manufacture protected bytes");
                require(protocol.equals(imported.getOriginalSmimeDetails().getSmimeProtocol()), "The source declaration must remain available");
                require(imported.getOriginalSmimeDetails().getVerificationStatus() == OriginalSmimeDetails.VerificationStatus.UNKNOWN,
                        "Unvisited protection facts must remain unknown");
            }
            require(imported.getProblems().stream().anyMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED),
                    "The limit stop must remain observable");
        }

        private static void assertUnavailableOpenPgpCaptureNeedsACompleteProjection(final SimpleJavaMail mail) throws Exception {
            final jakarta.mail.internet.MimeMessage complete = protectedMessage(false);
            complete.saveChanges();
            final byte[] completeBytes = EmailConverter.mimeMessageToEMLByteArray(complete);
            final ImportLimitsConfig fitting = ImportLimitsConfig.builder().withMaximumPartCount(3).build();
            final Email admitted = mail.converter().fromMimeMessage(complete).withImportLimitsConfig(fitting).toEmail();
            require(Arrays.equals(admitted.getOriginalOpenPgpDetails().getOriginalProtectedMessage(), completeBytes),
                    "A fully admitted bounded supplied message must retain its complete protected representation");
            require(admitted.getPlainText().contains("Readable limited body") && admitted.getAttachments().size() == 1
                    && "signature.asc".equals(admitted.getAttachments().get(0).getName()),
                    "A fitting import retains the existing body and signature-attachment collection semantics");
            assertUnsavedMetadataIsObservedBeforeDiagnosticSerialization(mail, fitting);

            final Email captured = mail.converter().fromEml(completeBytes)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();
            require(Arrays.equals(captured.getOriginalOpenPgpDetails().getOriginalProtectedMessage(), completeBytes),
                    "Already captured raw bytes must survive a later traversal stop");

            final jakarta.mail.internet.MimeMessage invalidEncoding = protectedMessage(true);
            invalidEncoding.addHeader("Content-Transfer-Encoding", "8bit");
            invalidEncoding.addHeader("Content-Transfer-Encoding", "binary");
            final Email incomplete = mail.converter().fromMimeMessage(invalidEncoding).withImportLimitsConfig(fitting).toEmail();
            require(incomplete.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "An unreadable container must not serialize unvisited children for protected-byte evidence");
            require(incomplete.getProblems().stream().noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED),
                    "An unreadable container is not evidence of a limit stop");

            final jakarta.mail.internet.MimeMessage foreign = populateProtectedMessage(new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(new java.util.Properties())) {
                @Override public java.io.InputStream getRawInputStream() {
                    throw new AssertionError("Unvisited foreign raw content was acquired for diagnostics");
                }
            }, true, new jakarta.mail.internet.MimeMultipart("signed"));
            foreign.addHeader("Content-Transfer-Encoding", "8bit");
            foreign.addHeader("Content-Transfer-Encoding", "binary");
            final Email foreignProjection = mail.converter().fromMimeMessage(foreign).withImportLimitsConfig(fitting).toEmail();
            require(foreignProjection.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "Incomplete foreign MIME must keep unavailable bytes unavailable without new raw-source reads");

            final jakarta.mail.internet.MimeMessage unreadableChild = protectedMessage(true, new jakarta.mail.internet.MimeMultipart("signed") {
                @Override public synchronized jakarta.mail.BodyPart getBodyPart(final int index) throws jakarta.mail.MessagingException {
                    if (index == 1) throw new jakarta.mail.MessagingException("fixture child was unavailable");
                    return super.getBodyPart(index);
                }
            });
            final Email partial = mail.converter().fromMimeMessage(unreadableChild).withImportLimitsConfig(fitting).toEmail();
            require(partial.getPlainText().contains("Readable limited body"), "Completed neighbors of an unavailable child must survive");
            require(partial.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "Admitting a slot whose child was unavailable is not a complete projection");
            require(partial.getProblems().stream().noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED),
                    "Child-access recovery must remain distinct from a limit stop");

            final jakarta.mail.internet.MimeMessage opaque = protectedMessage(false);
            final jakarta.mail.internet.MimeMultipart root = (jakarta.mail.internet.MimeMultipart) opaque.getContent();
            root.removeBodyPart(1);
            final jakarta.mail.internet.MimeMultipart nested = new jakarta.mail.internet.MimeMultipart("mixed");
            nested.addBodyPart(((jakarta.mail.internet.MimeMultipart) protectedMessage(true).getContent()).getBodyPart(1));
            final jakarta.mail.internet.MimeBodyPart attachedContainer = new jakarta.mail.internet.MimeBodyPart();
            attachedContainer.setContent(nested);
            attachedContainer.setDisposition("attachment");
            attachedContainer.setFileName("opaque.multipart");
            root.addBodyPart(attachedContainer);
            final Email opaqueProjection = mail.converter().fromMimeMessage(opaque).withFetchAttachmentData(false)
                    .withImportLimitsConfig(fitting).toEmail();
            require(opaqueProjection.getPlainText().contains("Readable limited body"), "An opaque attachment must not suppress the readable body");
            require(opaqueProjection.getAttachments().size() == 1, "The admitted opaque attachment remains available");
            require(opaqueProjection.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "An admitted opaque container does not prove that its nested children were admitted");

            final jakarta.mail.internet.MimeMessage unexpectedText = protectedMessage(false);
            final jakarta.mail.internet.MimeMultipart unexpectedRoot = (jakarta.mail.internet.MimeMultipart) unexpectedText.getContent();
            unexpectedRoot.removeBodyPart(0);
            final jakarta.mail.internet.MimeMultipart unvisited = new jakarta.mail.internet.MimeMultipart("mixed");
            unvisited.addBodyPart(((jakarta.mail.internet.MimeMultipart) protectedMessage(true).getContent()).getBodyPart(1));
            final jakarta.mail.internet.MimeBodyPart undeclaredContainer = new jakarta.mail.internet.MimeBodyPart();
            undeclaredContainer.setDataHandler(new jakarta.activation.DataHandler(unvisited, "text/plain") {
                @Override public Object getContent() { return unvisited; }
                @Override public void writeTo(final java.io.OutputStream output) throws java.io.IOException {
                    try { unvisited.writeTo(output); }
                    catch (jakarta.mail.MessagingException failed) { throw new java.io.IOException(failed); }
                }
            });
            unexpectedRoot.addBodyPart(undeclaredContainer, 0);
            unexpectedRoot.getBodyPart(1).setHeader("Content-Type", "application/pgp-signature");
            unexpectedRoot.getBodyPart(1).setHeader("Content-Transfer-Encoding", "7bit");
            final Email unexpectedProjection = mail.converter().fromMimeMessage(unexpectedText).withFetchAttachmentData(false)
                    .withImportLimitsConfig(fitting).toEmail();
            require(unexpectedProjection.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "An unexpected structured text-handler value must not permit serialization of its unvisited children");
            require(unexpectedProjection.getProblems().stream().noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED),
                    "The opaque handler control must not depend on a budget omission");

            final jakarta.mail.internet.MimeMessage laterStop = protectedMessage(true);
            final jakarta.mail.internet.MimeBodyPart deferred = new jakarta.mail.internet.MimeBodyPart();
            deferred.setDataHandler(new jakarta.activation.DataHandler(new jakarta.activation.DataSource() {
                @Override public java.io.InputStream getInputStream() { throw new AssertionError("Deferred admitted payload was read for evidence"); }
                @Override public java.io.OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                @Override public String getContentType() { return "application/octet-stream"; }
                @Override public String getName() { return "deferred.dat"; }
            }));
            deferred.setDisposition("attachment");
            deferred.setFileName("deferred.dat");
            deferred.setHeader("Content-Type", "application/octet-stream");
            deferred.setHeader("Content-Transfer-Encoding", "binary");
            ((jakarta.mail.internet.MimeMultipart) laterStop.getContent()).addBodyPart(deferred, 1);
            final Email stoppedLater = mail.converter().fromMimeMessage(laterStop).withFetchAttachmentData(false)
                    .withImportLimitsConfig(fitting).toEmail();
            require(stoppedLater.getAttachments().size() == 1
                    && stoppedLater.getOriginalOpenPgpDetails().getOriginalProtectedMessage() == null,
                    "A later limit stop leaves an admitted deferred resource borrowed without diagnostic acquisition");
        }

        private static jakarta.mail.internet.MimeMessage protectedMessage(final boolean unreadableSignature) throws Exception {
            return protectedMessage(unreadableSignature, new jakarta.mail.internet.MimeMultipart("signed"));
        }

        private static void assertUnsavedMetadataIsObservedBeforeDiagnosticSerialization(final SimpleJavaMail mail,
                final ImportLimitsConfig fitting) throws Exception {
            final jakarta.mail.internet.MimeMessage limitedInput = declaredUnsavedProtectedMessage();
            require(limitedInput.getSentDate() == null && limitedInput.getMessageID() == null, "The supplied metadata must start absent");
            final Email limited = mail.converter().fromMimeMessage(limitedInput).withImportLimitsConfig(fitting).toEmail();
            require(limited.getSentDate() == null && limited.getId() == null,
                    "A bounded import must not manufacture observed metadata from later diagnostic serialization");
            require(limited.getSourceData().getHeaders().stream().noneMatch(header ->
                    "Date".equalsIgnoreCase(header.getName()) || "Message-ID".equalsIgnoreCase(header.getName())),
                    "The captured supplied-header view must remain the originally observed view");
            require(limited.getOriginalOpenPgpDetails().getOriginalProtectedMessage() != null,
                    "A complete admitted projection can still retain a diagnostic protected representation");
            require(limitedInput.getSentDate() != null && limitedInput.getMessageID() != null,
                    "The control must exercise real later serializer-generated metadata");

            final Email legacy = mail.converter().fromMimeMessage(declaredUnsavedProtectedMessage()).toEmail();
            require(legacy.getSentDate() != null && legacy.getId() != null,
                    "The unlimited ordinary import keeps its existing pre-parse serialization behavior");
        }

        private static jakarta.mail.internet.MimeMessage declaredUnsavedProtectedMessage() throws Exception {
            final jakarta.mail.internet.MimeMessage message = protectedMessage(false);
            final jakarta.mail.internet.MimeMultipart parts = (jakarta.mail.internet.MimeMultipart) message.getContent();
            parts.getBodyPart(0).setHeader("Content-Type", "text/plain; charset=us-ascii");
            parts.getBodyPart(0).setHeader("Content-Transfer-Encoding", "7bit");
            parts.getBodyPart(1).setHeader("Content-Type", "application/pgp-signature");
            parts.getBodyPart(1).setHeader("Content-Transfer-Encoding", "7bit");
            return message;
        }

        private static jakarta.mail.internet.MimeMessage protectedMessage(final boolean unreadableSignature,
                final jakarta.mail.internet.MimeMultipart parts) throws Exception {
            return populateProtectedMessage(new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(new java.util.Properties())), unreadableSignature, parts);
        }

        private static jakarta.mail.internet.MimeMessage populateProtectedMessage(final jakarta.mail.internet.MimeMessage message,
                final boolean unreadableSignature, final jakarta.mail.internet.MimeMultipart parts) throws Exception {
            message.setFrom("author@example.org");
            final jakarta.mail.internet.MimeBodyPart text = new jakarta.mail.internet.MimeBodyPart();
            text.setText("Readable limited body");
            parts.addBodyPart(text);
            final jakarta.mail.internet.MimeBodyPart signature = new jakarta.mail.internet.MimeBodyPart();
            signature.setDisposition("attachment");
            signature.setFileName("signature.asc");
            if (unreadableSignature) {
                signature.setDataHandler(new jakarta.activation.DataHandler(new jakarta.activation.DataSource() {
                    @Override public java.io.InputStream getInputStream() { throw new AssertionError("Unvisited signature was read for diagnostics"); }
                    @Override public java.io.OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                    @Override public String getContentType() { return "application/pgp-signature"; }
                    @Override public String getName() { return "signature.asc"; }
                }));
            } else {
                signature.setText("uninspected signature fixture");
            }
            parts.addBodyPart(signature);
            message.setContent(parts);
            message.setHeader("Content-Type", parts.getContentType() + "; protocol=\"application/pgp-signature\"");
            return message;
        }

        private static void assertBoundedUnreadableSmimeContainerDoesNotReadChildren(final SimpleJavaMail mail, final String protocol) throws Exception {
            final jakarta.mail.internet.MimeMessage message = protectedMessage(true);
            message.setHeader("Content-Type", ((jakarta.mail.internet.MimeMultipart) message.getContent()).getContentType()
                    + "; protocol=\"" + protocol + "\"");
            message.addHeader("Content-Transfer-Encoding", "8bit");
            message.addHeader("Content-Transfer-Encoding", "binary");
            final Email imported = mail.converter().fromMimeMessage(message)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(3).build()).toEmail();
            require(imported.getOriginalSmimeDetails().getOriginalProtectedMessage() == null,
                    "Unavailable S/MIME evidence must not serialize children hidden by container recovery");
            require(imported.getProblems().stream().noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED),
                    "Container recovery alone must not invent a limit stop");
        }

        private static void assertModulePresence(final String implementation, final boolean expected) throws Exception {
            try {
                Class.forName(implementation, false, Probe.class.getClassLoader());
                require(expected, "Implementation must be physically absent: " + implementation);
            } catch (ClassNotFoundException absent) {
                require(!expected, "Control implementation must remain present: " + implementation);
            }
        }

        private static void assertFacts(final Email exact, final Specimen specimen, final byte[] source) {
            final OriginalSmimeDetails smime = exact.getOriginalSmimeDetails();
            final OriginalOpenPgpDetails openPgp = exact.getOriginalOpenPgpDetails();
            if (specimen.family == Family.SMIME) {
                require(smime.getSmimeMode().name().equals(specimen.mode), specimen.name + ": S/MIME declaration was lost");
                require(smime.getVerificationStatus() == OriginalSmimeDetails.VerificationStatus.UNKNOWN,
                        specimen.name + ": absent S/MIME cannot establish verification");
                require(smime.getDecryptionStatus() == OriginalSmimeDetails.DecryptionStatus.UNKNOWN,
                        specimen.name + ": absent S/MIME cannot establish decryption");
                require(Arrays.equals(smime.getOriginalProtectedMessage(), source), specimen.name + ": original S/MIME bytes changed");
            } else {
                require(smime.getSmimeMode() == OriginalSmimeDetails.SmimeMode.PLAIN, specimen.name + ": unrelated S/MIME mode changed");
            }
            if (specimen.family == Family.OPENPGP) {
                require(openPgp.getOpenPgpMode().name().equals(specimen.mode), specimen.name + ": OpenPGP declaration was lost");
                require(openPgp.getSignatureStatus() == OriginalOpenPgpDetails.SignatureStatus.UNKNOWN,
                        specimen.name + ": absent OpenPGP cannot establish verification");
                require(openPgp.getDecryptionStatus() == OriginalOpenPgpDetails.DecryptionStatus.UNKNOWN,
                        specimen.name + ": an outer signature does not establish absence of inner encryption");
                require(Arrays.equals(openPgp.getOriginalProtectedMessage(), source), specimen.name + ": original OpenPGP bytes changed");
            } else {
                require(openPgp.getOpenPgpMode() == OriginalOpenPgpDetails.OpenPgpMode.PLAIN, specimen.name + ": unrelated OpenPGP mode changed");
            }
        }

        private static void assertRawView(final Email exact, final Specimen specimen) {
            require(exact.getSubject().equals("Exact absent protection"), specimen.name + ": raw subject changed");
            require(exact.getFromRecipient().getAddress().equals("author@example.org"), specimen.name + ": raw sender changed");
            require(exact.getRecipients().size() == 2, specimen.name + ": raw To/Bcc view changed");
            require(exact.getOverrideReceivers().size() == 1
                    && exact.getOverrideReceivers().get(0).getAddress().equals("relay@example.org"), specimen.name + ": explicit envelope changed");
            require(exact.getDecryptedAttachments().isEmpty(), specimen.name + ": inspection must not add decrypted attachments");
            require(exact.getSmimeSignedEmail() == null, specimen.name + ": inspection must not replace the raw signed view");
            require(specimen.rawPlainText == null ? exact.getPlainText() == null
                    : exact.getPlainText() != null && exact.getPlainText().contains(specimen.rawPlainText), specimen.name + ": raw body view changed");
        }

        private static Email roundTrip(final Email exact) throws Exception {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(exact);
            }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (Email) input.readObject();
            }
        }

        private static void require(final boolean condition, final String description) {
            if (!condition) throw new AssertionError(description);
        }
    }

    private static List<Specimen> specimens(final RuntimeProfile profile) {
        final List<Specimen> specimens = new ArrayList<>();
        specimens.add(new Specimen("plain", Family.NONE, "PLAIN", "text/plain; charset=us-ascii", "plain raw body\r\n", "plain raw body"));
        if (!profile.smimePresent) {
            specimens.add(new Specimen("smime-opaque-signed", Family.SMIME, "SIGNED", "application/pkcs7-mime; smime-type=signed-data",
                    "synthetic signed container\r\n", null));
            specimens.add(new Specimen("smime-encrypted", Family.SMIME, "ENCRYPTED", "application/x-pkcs7-mime; smime-type=enveloped-data",
                    "synthetic encrypted container\r\n", null));
            specimens.add(new Specimen("smime-ambiguous", Family.SMIME, "UNKNOWN", "application/pkcs7-mime",
                    "synthetic ambiguous container\r\n", null));
            specimens.add(new Specimen("smime-detached-signed", Family.SMIME, "SIGNED",
                    "multipart/signed; protocol=\"application/pkcs7-signature\"; boundary=\"smime\"",
                    signedBody("smime", "application/pkcs7-signature", "Content-Type: text/plain; charset=us-ascii\r\n\r\nvisible raw body\r\n"),
                    "visible raw body"));
        }
        if (!profile.openPgpPresent) {
            specimens.add(new Specimen("openpgp-signed", Family.OPENPGP, "SIGNED",
                    "multipart/signed; protocol=\"application/pgp-signature\"; boundary=\"pgp\"",
                    signedBody("pgp", "application/pgp-signature", "Content-Type: text/plain; charset=us-ascii\r\n\r\nvisible raw body\r\n"),
                    "visible raw body"));
            specimens.add(new Specimen("openpgp-encrypted", Family.OPENPGP, "ENCRYPTED",
                    "multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"encrypted\"", encryptedBody(), null));
            specimens.add(new Specimen("openpgp-signed-outer-encrypted-inner", Family.OPENPGP, "SIGNED",
                    "multipart/signed; protocol=\"application/pgp-signature\"; boundary=\"outer\"",
                    signedBody("outer", "application/pgp-signature",
                            "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"encrypted\"\r\n\r\n" + encryptedBody()), null));
        }
        return specimens;
    }

    private static String signedBody(final String boundary, final String signatureType, final String entity) {
        return "--" + boundary + "\r\n" + entity + "\r\n--" + boundary + "\r\nContent-Type: " + signatureType
                + "\r\n\r\nsynthetic signature\r\n--" + boundary + "--\r\n";
    }

    private static String encryptedBody() {
        return "--encrypted\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n"
                + "--encrypted\r\nContent-Type: application/octet-stream\r\n\r\nsynthetic ciphertext\r\n--encrypted--\r\n";
    }

    private static byte[] source(final Specimen specimen) {
        return ("From: author@example.org\r\nTo: visible@example.org\r\nBcc: hidden@example.org\r\n"
                + "Subject: Exact absent protection\r\nX-Raw: unchanged\r\nMIME-Version: 1.0\r\nContent-Type: "
                + specimen.contentType + "\r\n\r\n" + specimen.body).getBytes(US_ASCII);
    }

    private enum RuntimeProfile {
        BOTH_ABSENT(false, false), SMIME_ABSENT(false, true), OPENPGP_ABSENT(true, false);

        private final boolean smimePresent;
        private final boolean openPgpPresent;

        RuntimeProfile(final boolean smimePresent, final boolean openPgpPresent) {
            this.smimePresent = smimePresent;
            this.openPgpPresent = openPgpPresent;
        }
    }

    private enum Family { NONE, SMIME, OPENPGP }

    private static final class Specimen {
        private final String name;
        private final Family family;
        private final String mode;
        private final String contentType;
        private final String body;
        private final String rawPlainText;

        private Specimen(final String name, final Family family, final String mode, final String contentType, final String body,
                final String rawPlainText) {
            this.name = name;
            this.family = family;
            this.mode = mode;
            this.contentType = contentType;
            this.body = body;
            this.rawPlainText = rawPlainText;
        }
    }
}
