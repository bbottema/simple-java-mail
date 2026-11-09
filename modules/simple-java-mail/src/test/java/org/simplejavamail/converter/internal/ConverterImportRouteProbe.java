package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/** Test-only access to contextual import seams; production methods retain their package visibility. */
public final class ConverterImportRouteProbe {
    private ConverterImportRouteProbe() { }

    public static void stubMimeImport(final ConfiguredEmailConverterImpl converter, final EmailPopulatingBuilder imported) {
        doReturn(imported).when(converter).mimeMessageToEmailBuilder(any(MimeMessage.class), nullable(Pkcs12Config.class),
                nullable(OpenPgpReceiveConfig.class), anyBoolean(), any(ImportContext.class));
    }

    public static void verifyMimeImport(final ConfiguredEmailConverterImpl converter, final Session expectedSession,
            final Pkcs12Config expectedStore, final OpenPgpReceiveConfig expectedPgp, final boolean expectedFetch) throws Exception {
        final ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        final ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(converter).mimeMessageToEmailBuilder(message.capture(), same(expectedStore), same(expectedPgp), eq(expectedFetch), context.capture());
        assertThat(message.getValue().getSession()).isSameAs(expectedSession);
        assertThat(message.getValue().getSubject()).isEqualTo("Source");
        assertUnboundedImport(context.getValue());
    }

    public static void stubOutlookImport(final ConfiguredEmailConverterImpl converter,
            final OutlookEmailConversionResult imported, final byte[] expectedSource) {
        doAnswer(invocation -> {
            final InputStream source = invocation.getArgument(0);
            assertThat(source.readAllBytes()).containsExactly(expectedSource);
            return imported;
        }).when(converter).outlookMsgToEmailBuilderWithOutlookData(any(InputStream.class), nullable(Pkcs12Config.class), any(ImportContext.class));
    }

    public static void verifyOutlookImport(final ConfiguredEmailConverterImpl converter, final Pkcs12Config expectedStore) {
        final ArgumentCaptor<ImportContext> context = ArgumentCaptor.forClass(ImportContext.class);
        verify(converter).outlookMsgToEmailBuilderWithOutlookData(any(InputStream.class), same(expectedStore), context.capture());
        assertUnboundedImport(context.getValue());
    }

    private static void assertUnboundedImport(final ImportContext context) {
        assertThat(context).isNotNull();
        assertThat(context.maximumInputBytes).isZero();
        assertThat(context.traversal.hasLimits()).isFalse();
    }
}
