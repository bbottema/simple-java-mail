package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupedEmailApiBoundaryTest {
    @TempDir Path output;

    @Test
    void ordinaryAndExactEmailConfigurationGroupsCompileWithOnlyCore() throws Exception {
        assertThat(compile("class GroupedEmailConsumer { void configure("
                + "org.simplejavamail.api.email.EmailPopulatingBuilder email, org.simplejavamail.api.email.ExactEmailBuilder exact) {"
                + "email.withImageResolutionConfig(org.simplejavamail.api.email.config.ImageResolutionConfig.builder()"
                + ".withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(\"images\").build());"
                + "email.withContentEncodingConfig(org.simplejavamail.api.email.config.ContentEncodingConfig.builder()"
                + ".withContentTransferEncoding(org.simplejavamail.api.email.ContentTransferEncoding.BASE_64).build());"
                + "email.withTemplateOptions(org.simplejavamail.api.email.config.EmailTemplateOptions.builder().ignoringDefaults().build());"
                + "org.simplejavamail.api.email.config.SmtpDeliveryOptions smtp = org.simplejavamail.api.email.config.SmtpDeliveryOptions.builder()"
                + ".withTlsRequiredForOnwardDelivery().withSendingToAcceptedRecipients(false).build();"
                + "email.withSmtpDeliveryOptions(smtp); exact.withSmtpDeliveryOptions(smtp);"
                + "email.useConfiguredImageResolutionConfig().getImageResolutionConfig().toBuilder().clearEmbeddedImageBaseDir();"
                + "exact.useConfiguredSmtpDeliveryOptions().getSmtpDeliveryOptions().getSendingToAcceptedRecipients();"
                + "} }" )).isTrue();
    }

    @Test
    void fiveCryptoEntrypointsRemainTypedWithOnlyCore() throws Exception {
        assertThat(compile("class GroupedEmailConsumer { void configure("
                + "org.simplejavamail.api.email.EmailPopulatingBuilder email, "
                + "org.simplejavamail.api.email.config.DkimConfig dkim, "
                + "org.simplejavamail.api.email.config.SmimeSigningConfig smimeSigning, "
                + "org.simplejavamail.api.email.config.SmimeEncryptionConfig smimeEncryption, "
                + "org.simplejavamail.api.email.config.OpenPgpSigningConfig pgpSigning, "
                + "org.simplejavamail.api.email.config.OpenPgpEncryptionConfig pgpEncryption) {"
                + "email.signWithDomainKey(dkim).signWithSmime(smimeSigning).encryptWithSmime(smimeEncryption)"
                + ".signWithOpenPgp(pgpSigning).encryptWithOpenPgp(pgpEncryption); } }" )).isTrue();
    }

    @Test
    void advancedSettingsAndTheirInspectionCannotBeCalledOnTheSupportedRootBuilders() throws Exception {
        for (final String invocation : List.of(
                "email.ignoringDefaults()", "email.ignoringOverrides()", "email.isIgnoreDefaults()",
                "email.getPropertiesNotToApplyDefaultValueFor()", "email.withEmbeddedImageAutoResolutionForFiles(true)",
                "email.clearEmbeddedImageBaseDir()", "email.withContentTransferEncoding(null)",
                "email.getContentTransferEncoding()", "email.withDeliveryStatusNotification((org.simplejavamail.api.email.config.DeliveryStatusNotification) null)",
                "email.fixingEnvelopeId(\"id\")", "email.withTlsRequiredForOnwardDelivery()", "email.withSendingToAcceptedRecipients(true)",
                "email.signWithDomainKey(new byte[] {1}, \"example.org\", \"selector\", null)",
                "email.signWithSmime((java.io.File) null, \"store\", \"alias\", \"key\", null)",
                "email.signWithSmime((java.nio.file.Path) null, \"store\", \"alias\", \"key\", null)",
                "email.encryptWithSmime((java.io.File) null, null, null)",
                "email.encryptWithSmime((java.nio.file.Path) null, null, null)",
                "exact.withTlsRequiredForOnwardDelivery()", "exact.getSendingToAcceptedRecipients()")) {
            assertThat(compile("class GroupedEmailConsumer { void configure("
                    + "org.simplejavamail.api.email.EmailPopulatingBuilder email, org.simplejavamail.api.email.ExactEmailBuilder exact) {"
                    + invocation + "; } }" )).as(invocation).isFalse();
        }
    }

    private boolean compile(final String source) throws Exception {
        final Path file = output.resolve("GroupedEmailConsumer.java");
        Files.writeString(file, source);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            return compiler.getTask(null, files, diagnostics,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, files.getJavaFileObjects(file)).call();
        }
    }
}
