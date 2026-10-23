package org.simplejavamail.dkimprobe;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.mailer.MailerHelper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Properties;

/** Exercises SJM's public helper, real module loading and repeatable signed bytes with Angus absent. */
public final class DkimConsumer {
    private DkimConsumer() { }
    public static void main(String[] args) throws Exception {
        try { Class.forName("org.eclipse.angus.mail.smtp.SMTPMessage"); throw new AssertionError("Angus present"); }
        catch (ClassNotFoundException expected) { }
        if (Security.getProvider("BC") != null) { throw new AssertionError("BC already registered"); }
        Provider[] providers = Security.getProviders();
        for (String algorithm : new String[] {"SHA256_WITH_RSA", "SHA256_WITH_ED25519"}) {
            boolean ed = algorithm.endsWith("ED25519");
            KeyPairGenerator generator = ed ? KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider())
                    : KeyPairGenerator.getInstance("RSA");
            if (!ed) { generator.initialize(1024); }
            KeyPair key = generator.generateKeyPair();
            DkimConfig config = DkimConfig.builder().dkimPrivateKeyData(key.getPrivate().getEncoded())
                    .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("mail").signingAlgorithm(algorithm).build();
            Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                    .from("sender@supersecret-testing-domain.com").to("receiver@example.net").signWithDomainKey(config).buildEmail();
            byte[] raw = ("From: sender@supersecret-testing-domain.com\r\nTo: receiver@example.net\r\n"
                    + "Date: Thu, 1 Jan 1970 00:00:00 +0000\r\nMessage-ID: <neutral-sjm@example.com>\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\nbody\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw)) {
                @Override public void saveChanges() { }
                @Override public void writeTo(OutputStream output) throws IOException { output.write(raw); }
                @Override public void writeTo(OutputStream output, String[] ignore) throws IOException { output.write(raw); }
            };
            MimeMessage signed = MailerHelper.signMessageWithDKIM(input, email);
            ByteArrayOutputStream first = new ByteArrayOutputStream();
            ByteArrayOutputStream second = new ByteArrayOutputStream();
            signed.writeTo(first);
            signed.saveChanges();
            signed.writeTo(second);
            byte[] wire = first.toByteArray();
            if (!Arrays.equals(wire, second.toByteArray())) { throw new AssertionError("Repeated signed writes changed"); }
            if (!Arrays.equals(raw, Arrays.copyOfRange(wire, wire.length - raw.length, wire.length))) {
                throw new AssertionError("Finalized source bytes changed");
            }
            SignatureAssertions.assertValidSignature(wire, key.getPublic());
        }
        if (!Arrays.equals(providers, Security.getProviders())) { throw new AssertionError("SJM changed JVM providers"); }
    }
}
