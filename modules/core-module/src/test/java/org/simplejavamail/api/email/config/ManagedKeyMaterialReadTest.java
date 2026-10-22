package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedKeyMaterialReadTest {

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Reader.class)
    void ownedPathOpeningFailuresUsePreparationKindAndKeepTheOriginalCause(final Reader reader) throws Exception {
        final IOException failure = new IOException("key source unavailable");
        final Path path = pathOpening(null, failure);

        assertPreparationFailure(catchThrowable(() -> reader.fromPath(path)), failure);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void borrowedReadFailuresUsePreparationKindWithoutClosingTheSuppliedStream(final Reader reader) throws Exception {
        final IOException failure = new IOException("key read failed");
        final TrackingInputStream input = new TrackingInputStream(reader.content(), failure, null, null);

        assertPreparationFailure(catchThrowable(() -> reader.fromStream(input)), failure);
        assertThat(input.closed).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void ownedReadFailuresCloseTheStreamAndKeepSuppressedCloseFailuresOnTheOriginalCause(final Reader reader) throws Exception {
        final IOException readFailure = new IOException("key read failed");
        final IOException closeFailure = new IOException("key close failed");
        final TrackingInputStream input = new TrackingInputStream(reader.content(), readFailure, closeFailure, null);
        final Path path = pathOpening(input, null);

        assertPreparationFailure(catchThrowable(() -> reader.fromPath(path)), readFailure);
        assertThat(input.closed).isTrue();
        assertThat(readFailure.getSuppressed()).containsExactly(closeFailure);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void aClosingFailureAfterASuccessfulOwnedReadStillFailsTheOperation(final Reader reader) throws Exception {
        final IOException closeFailure = new IOException("key close failed");
        final TrackingInputStream input = new TrackingInputStream(reader.content(), null, closeFailure, null);

        assertPreparationFailure(catchThrowable(() -> reader.fromPath(pathOpening(input, null))), closeFailure);
        assertThat(input.closed).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void successfulReadsKeepTheOwnershipBoundaryAndDoNotInterpretOpaqueKeyMaterial(final Reader reader) throws Exception {
        final TrackingInputStream borrowed = new TrackingInputStream(reader.content(), null, null, null);
        final TrackingInputStream owned = new TrackingInputStream(reader.content(), null, null, null);

        final Object borrowedValue = reader.fromStream(borrowed);
        final Object ownedValue = reader.fromPath(pathOpening(owned, null));

        assertThat(borrowedValue).isEqualTo(ownedValue);
        if (reader != Reader.SMIME_ENCRYPTION) {
            assertThat((byte[]) borrowedValue).containsExactly(1, 2, 3);
        }
        assertThat(borrowed.closed).isFalse();
        assertThat(owned.closed).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void applicationRuntimeFailuresAreNotNormalizedAndOwnedStreamsStillClose(final Reader reader) throws Exception {
        final IllegalStateException failure = new IllegalStateException("application stream failure");
        final IOException closeFailure = new IOException("key close failed");
        final TrackingInputStream borrowed = new TrackingInputStream(reader.content(), null, null, failure);
        final TrackingInputStream owned = new TrackingInputStream(reader.content(), null, closeFailure, failure);

        assertThat(catchThrowable(() -> reader.fromStream(borrowed))).isSameAs(failure);
        assertThat(borrowed.closed).isFalse();
        assertThat(catchThrowable(() -> reader.fromPath(pathOpening(owned, null)))).isSameAs(failure);
        assertThat(owned.closed).isTrue();
        assertThat(failure.getSuppressed()).containsExactly(closeFailure);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void fileOverloadsRetainTheManagedFailureContractAndCloseSuccessfulReads(final Reader reader) throws Exception {
        final Path path = directory.resolve("key-material");
        assertThatThrownBy(() -> reader.fromFile(path.toFile())).isInstanceOf(MailException.class)
                .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.PREPARATION));

        Files.write(path, reader.content());
        reader.fromFile(path.toFile());
        Files.delete(path);
        assertThat(Files.exists(path)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Reader.class, names = {"DKIM", "SMIME_ENCRYPTION", "PKCS12"})
    void stringPathOverloadsRetainTheManagedFailureContractAndCloseSuccessfulReads(final Reader reader) throws Exception {
        final Path path = directory.resolve("key-material");
        assertThatThrownBy(() -> reader.fromStringPath(path.toString())).isInstanceOf(MailException.class)
                .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.PREPARATION));

        Files.write(path, reader.content());
        reader.fromStringPath(path.toString());
        Files.delete(path);
        assertThat(Files.exists(path)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void missingArgumentsRemainArgumentFailures(final Reader reader) {
        assertThatThrownBy(() -> reader.fromPath(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void malformedCertificateInputRetainsItsExistingParsingFailureAndBorrowedOwnership() {
        final TrackingInputStream input = new TrackingInputStream(new byte[]{1, 2, 3}, null, null, null);

        assertThatThrownBy(() -> SmimeEncryptionConfig.builder().x509Certificate(input))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(CertificateException.class);
        assertThat(input.closed).isFalse();
    }

    private static void assertPreparationFailure(final Throwable failure, final IOException cause) {
        assertThat(failure).isInstanceOf(MailException.class).hasCause(cause);
        final MailException mailFailure = (MailException) failure;
        assertThat(mailFailure.getKind()).isEqualTo(MailFailureKind.PREPARATION);
        assertThat(mailFailure.getSubmissionReceipt()).isEmpty();
    }

    private static Path pathOpening(final InputStream input, final IOException failure) throws IOException {
        final Path path = mock(Path.class);
        final FileSystem fileSystem = mock(FileSystem.class);
        final FileSystemProvider provider = mock(FileSystemProvider.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(path.toString()).thenReturn("test key-material path");
        if (failure == null) {
            when(provider.newInputStream(path)).thenReturn(input);
        } else {
            when(provider.newInputStream(path)).thenThrow(failure);
        }
        return path;
    }

    private enum Reader {
        DKIM {
            @Override Object fromPath(final Path path) { return DkimConfig.builder().dkimPrivateKeyPath(path).getDkimPrivateKeyData(); }
            @Override Object fromFile(final File file) { return DkimConfig.builder().dkimPrivateKeyPath(file).getDkimPrivateKeyData(); }
            @Override Object fromStringPath(final String path) { return DkimConfig.builder().dkimPrivateKeyPath(path).getDkimPrivateKeyData(); }
            @Override Object fromStream(final InputStream input) { return DkimConfig.builder().dkimPrivateKeyData(input).getDkimPrivateKeyData(); }
        },
        SMIME_SIGNING {
            @Override Object fromPath(final Path path) { return SmimeSigningConfig.builder().pkcs12Config(path, "store", "key", "password").getPkcs12Config().getPkcs12StoreData(); }
            @Override Object fromFile(final File file) { return SmimeSigningConfig.builder().pkcs12Config(file, "store", "key", "password").getPkcs12Config().getPkcs12StoreData(); }
            @Override Object fromStream(final InputStream input) { return SmimeSigningConfig.builder().pkcs12Config(input, "store", "key", "password").getPkcs12Config().getPkcs12StoreData(); }
        },
        SMIME_ENCRYPTION {
            @Override Object fromPath(final Path path) { return SmimeEncryptionConfig.builder().x509Certificate(path).getX509Certificate(); }
            @Override Object fromFile(final File file) { return SmimeEncryptionConfig.builder().x509Certificate(file).getX509Certificate(); }
            @Override Object fromStringPath(final String path) { return SmimeEncryptionConfig.builder().x509Certificate(path).getX509Certificate(); }
            @Override Object fromStream(final InputStream input) { return SmimeEncryptionConfig.builder().x509Certificate(input).getX509Certificate(); }
            @Override byte[] content() throws IOException {
                try (InputStream input = ManagedKeyMaterialReadTest.class.getResourceAsStream("/managed-key-material-certificate.pem")) {
                    return input.readAllBytes();
                }
            }
        },
        PKCS12 {
            @Override Object fromPath(final Path path) { return Pkcs12Config.builder().pkcs12Store(path).getPkcs12StoreData(); }
            @Override Object fromFile(final File file) { return Pkcs12Config.builder().pkcs12Store(file).getPkcs12StoreData(); }
            @Override Object fromStringPath(final String path) { return Pkcs12Config.builder().pkcs12Store(path).getPkcs12StoreData(); }
            @Override Object fromStream(final InputStream input) throws IOException { return Pkcs12Config.builder().pkcs12Store(input).getPkcs12StoreData(); }
        };

        abstract Object fromPath(Path path);
        abstract Object fromFile(File file);
        Object fromStringPath(final String path) { throw new UnsupportedOperationException(); }
        abstract Object fromStream(InputStream input) throws IOException;
        byte[] content() throws IOException { return new byte[]{1, 2, 3}; }
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream content;
        private final IOException readFailure;
        private final IOException closeFailure;
        private final RuntimeException runtimeFailure;
        private boolean closed;

        private TrackingInputStream(final byte[] content, final IOException readFailure,
                final IOException closeFailure, final RuntimeException runtimeFailure) {
            this.content = new ByteArrayInputStream(content);
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
            this.runtimeFailure = runtimeFailure;
        }

        @Override public int read() throws IOException {
            if (runtimeFailure != null) { throw runtimeFailure; }
            if (readFailure != null) { throw readFailure; }
            return content.read();
        }

        @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (runtimeFailure != null) { throw runtimeFailure; }
            if (readFailure != null) { throw readFailure; }
            return content.read(bytes, offset, length);
        }

        @Override public void close() throws IOException {
            closed = true;
            if (closeFailure != null) { throw closeFailure; }
        }
    }
}
