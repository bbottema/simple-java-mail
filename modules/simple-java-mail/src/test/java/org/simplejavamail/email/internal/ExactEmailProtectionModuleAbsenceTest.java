package org.simplejavamail.email.internal;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
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
