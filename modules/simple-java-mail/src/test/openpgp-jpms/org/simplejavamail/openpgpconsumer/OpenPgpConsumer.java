package org.simplejavamail.openpgpconsumer;

import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.api.mailer.MailFailureKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exercises actual OpenPGP activation and cryptography through the supported application API and packaged JARs. */
public final class OpenPgpConsumer {
    private OpenPgpConsumer() { }

    public static void main(final String[] args) throws Exception {
        final boolean named = "modulepath".equals(args[0]);
        if (SimpleJavaMail.class.getModule().isNamed() != named
                || OpenPgpConsumer.class.getModule().isNamed() != named) {
            throw new AssertionError("The requested deployment mode was not used");
        }
        if (named) {
            final Module implementation = ModuleLayer.boot().findModule("org.simplejavamail.openpgp")
                    .orElseThrow(() -> new AssertionError("OpenPGP was not resolved"));
            if (implementation.getDescriptor().isAutomatic()
                    || SimpleJavaMail.class.getModule().getDescriptor().isAutomatic()
                    || !SimpleJavaMail.class.getModule().canRead(implementation)) {
                throw new AssertionError("The packaged explicit modules were not used");
            }
        }

        final Path keys = Path.of(args[1]);
        final byte[] publicKey = Files.readAllBytes(keys.resolve("public-key.asc"));
        final byte[] privateKey = Files.readAllBytes(keys.resolve("private-key.asc"));
        final char[] passphrase = "openpgpjs-fixture-passphrase".toCharArray();
        final OpenPgpSigningConfig signing = OpenPgpSigningConfig.builder()
                .secretKeyRing(privateKey).passphrase(passphrase).build();
        final OpenPgpEncryptionConfig encryption = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(publicKey).build();
        final OpenPgpReceiveConfig receiving = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(publicKey).addDecryptionKeyRing(privateKey, passphrase).build();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

        if ("absent".equals(args[0])) {
            assertOptionalAbsence(mail, keys, signing, receiving);
            System.out.println("OpenPGP absent: ordinary import and explicit-request boundaries succeeded");
            return;
        }

        final Email signedSource = mail.emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org")
                .withPlainText("Packaged OpenPGP signing")
                .signWithOpenPgp(signing).buildEmail();
        final MimeMessage signed = mail.converter().fromEmail(signedSource).toMimeMessage();
        final byte[] signedBytes = mail.converter().fromMimeMessage(signed).toEmlBytes();
        assertReceived(mail.converter().fromEml(signedBytes).withOpenPgpReceiveConfig(receiving).toEmail(),
                "Packaged OpenPGP signing", false);
        if (!Arrays.equals(signedBytes, mail.converter().fromMimeMessage(signed).toEmlBytes())) {
            throw new AssertionError("Repeated protected output changed");
        }

        final Email encryptedSource = mail.emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org")
                .withPlainText("Packaged OpenPGP signing and encryption")
                .signWithOpenPgp(signing).encryptWithOpenPgp(encryption).buildEmail();
        final byte[] encryptedBytes = mail.converter().fromEmail(encryptedSource).toEmlBytes();
        assertReceived(mail.converter().fromEml(encryptedBytes).withOpenPgpReceiveConfig(receiving).toEmail(),
                "Packaged OpenPGP signing and encryption", true);
        if (named) {
            final Module implementation = ModuleLayer.boot().findModule("org.simplejavamail.openpgp").orElseThrow();
            final Module kotlin = ModuleLayer.boot().findModule("kotlin.stdlib")
                    .orElseThrow(() -> new AssertionError("The existing Kotlin runtime dependency was not resolved"));
            final String implementationPackage = "org.simplejavamail.internal.openpgpsupport";
            if (!implementation.isExported(implementationPackage, SimpleJavaMail.class.getModule())
                    || implementation.isExported(implementationPackage)
                    || implementation.isExported(implementationPackage, OpenPgpConsumer.class.getModule())
                    || implementation.isOpen(implementationPackage) || !implementation.canRead(kotlin)) {
                throw new AssertionError("OpenPGP implementation access is not narrowly qualified to SJM");
            }
        }
        System.out.println("OpenPGP " + args[0] + ": signing, verification, encryption and decryption succeeded");
    }

    private static void assertReceived(final Email email, final String body, final boolean encrypted) {
        final OriginalOpenPgpDetails details = email.getOriginalOpenPgpDetails();
        if (!body.equals(email.getPlainText()) || details == null
                || details.getSignatureStatus() != OriginalOpenPgpDetails.SignatureStatus.VALID
                || (encrypted && details.getDecryptionStatus() != OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED)) {
            throw new AssertionError("The protected message did not complete the expected public round trip");
        }
    }

    private static void assertOptionalAbsence(final SimpleJavaMail mail, final Path keys,
            final OpenPgpSigningConfig signing, final OpenPgpReceiveConfig receiving) throws Exception {
        final Email plain = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Readable without OpenPGP").buildEmail();
        final byte[] ordinary = mail.converter().fromEmail(plain).toEmlBytes();
        final Email imported = mail.converter().fromEml(ordinary).toEmail();
        if (!"Readable without OpenPGP".equals(imported.getPlainText())
                || imported.getOriginalOpenPgpDetails().getSignatureStatus() != OriginalOpenPgpDetails.SignatureStatus.NOT_PRESENT) {
            throw new AssertionError("Ordinary mail needs an absent optional module");
        }
        for (String fixture : new String[] {"signed", "encrypted"}) {
            final byte[] source = Files.readAllBytes(keys.resolve(fixture + "-mixed.eml"));
            final Email preserved = mail.converter().fromEml(source).toEmail();
            final OriginalOpenPgpDetails details = preserved.getOriginalOpenPgpDetails();
            if (details.getSignatureStatus() != OriginalOpenPgpDetails.SignatureStatus.UNKNOWN
                    || !Arrays.equals(source, details.getOriginalProtectedMessage())
                    || (fixture.equals("signed") && !"Héllo from OpenPGP.js".equals(preserved.getPlainText()))) {
                throw new AssertionError("Unavailable inspection lost its source or invented a signature fact");
            }
        }
        final Email requested = mail.emailBuilder().copying(plain).signWithOpenPgp(signing).buildEmail();
        assertMissingModule(() -> mail.converter().fromEmail(requested).toMimeMessage());
        assertMissingModule(() -> mail.converter().fromEml(ordinary).withOpenPgpReceiveConfig(receiving).toEmail());
    }

    private static void assertMissingModule(final Runnable operation) {
        try {
            operation.run();
        } catch (MailException expected) {
            if (expected.getKind() == MailFailureKind.CONFIGURATION && expected.getMessage().contains("OpenPGP")
                    && expected.getMessage().contains("openpgp-module")) {
                return;
            }
            throw expected;
        }
        throw new AssertionError("An explicit OpenPGP request silently ignored the missing module");
    }
}
