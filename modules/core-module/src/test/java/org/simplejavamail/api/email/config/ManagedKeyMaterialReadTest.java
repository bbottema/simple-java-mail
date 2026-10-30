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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
        assertThatThrownBy(() -> reader.fromPath(null))
                .isInstanceOf(reader == Reader.PKCS12 ? IllegalArgumentException.class : NullPointerException.class);
    }

    @Test
    void malformedCertificateInputRetainsItsExistingParsingFailureAndBorrowedOwnership() {
        final TrackingInputStream input = new TrackingInputStream(new byte[]{1, 2, 3}, null, null, null);

        assertThatThrownBy(() -> SmimeEncryptionConfig.builder().x509Certificate(input))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(CertificateException.class);
        assertThat(input.closed).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OwnedFailure.class)
    void failedDkimReplacementKeepsTheKeyAndSelector(final OwnedFailure failure) throws Exception {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder().dkimPrivateKeyData(new byte[]{9})
                .dkimSigningDomain("example.com").dkimSelector("current").signingAlgorithm("rsa-sha256");
        final DkimConfig completed = builder.build();

        failure.assertFailure(path -> builder.dkimPrivateKeyPath(path), Reader.DKIM.content());

        assertThat(builder.build()).isEqualTo(completed);
        builder.dkimPrivateKeyPath(pathOpening(new ByteArrayInputStream(Reader.DKIM.content()), null));
        assertThat(builder.getDkimPrivateKeyData()).containsExactly(1, 2, 3);
        assertThat(builder.getDkimSelector()).isEqualTo("current");
        assertThat(builder.getDkimSigningDomain()).isEqualTo("example.com");
        assertThat(builder.getSigningAlgorithm()).isEqualTo("rsa-sha256");
        assertThat(completed.getDkimPrivateKeyData()).containsExactly(9);
    }

    @ParameterizedTest
    @EnumSource(OwnedFailure.class)
    void failedSigningReplacementKeepsTheStorePasswordsAliasAndAlgorithm(final OwnedFailure failure) throws Exception {
        final Pkcs12Config original = originalStore();
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder()
                .pkcs12Config(original).signatureAlgorithm("SHA384withRSA");
        final SmimeSigningConfig completed = builder.build();

        failure.assertFailure(path -> builder.pkcs12Config(path, "next-store", "next-alias", "next-key"), Reader.SMIME_SIGNING.content());

        assertThat(builder.build()).isEqualTo(completed);
        assertThat(builder.getPkcs12Config()).isSameAs(original);
        builder.pkcs12Config(pathOpening(new ByteArrayInputStream(Reader.SMIME_SIGNING.content()), null),
                "next-store", "next-alias", "next-key");
        assertThat(builder.getPkcs12Config().getPkcs12StoreData()).containsExactly(1, 2, 3);
        assertThat(builder.getPkcs12Config().getStorePassword()).containsExactly("next-store".toCharArray());
        assertThat(builder.getPkcs12Config().getKeyAlias()).isEqualTo("next-alias");
        assertThat(builder.getPkcs12Config().getKeyPassword()).containsExactly("next-key".toCharArray());
        assertThat(builder.getSignatureAlgorithm()).isEqualTo("SHA384withRSA");
        assertThat(completed.getPkcs12Config()).isSameAs(original);
    }

    @ParameterizedTest
    @EnumSource(OwnedFailure.class)
    void failedEncryptionReplacementKeepsTheCertificateAndAlgorithms(final OwnedFailure failure) throws Exception {
        final X509Certificate original = originalCertificate();
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder()
                .x509Certificate(original).keyEncapsulationAlgorithm("RSA_OAEP_SHA256").cipherAlgorithm("AES256_CBC");
        final SmimeEncryptionConfig completed = builder.build();

        failure.assertFailure(path -> builder.x509Certificate(path), Reader.SMIME_ENCRYPTION.content());

        assertThat(builder.getX509Certificate()).isSameAs(original);
        assertThat(builder.build()).isEqualTo(completed);
        builder.x509Certificate(pathOpening(new ByteArrayInputStream(Reader.SMIME_ENCRYPTION.content()), null));
        assertThat(builder.getX509Certificate()).isEqualTo(Reader.SMIME_ENCRYPTION.fromStream(
                new ByteArrayInputStream(Reader.SMIME_ENCRYPTION.content())));
        assertThat(builder.getKeyEncapsulationAlgorithm()).isEqualTo("RSA_OAEP_SHA256");
        assertThat(builder.getCipherAlgorithm()).isEqualTo("AES256_CBC");
        assertThat(completed.getX509Certificate()).isSameAs(original);
    }

    @ParameterizedTest
    @EnumSource(OwnedFailure.class)
    void failedStoreReplacementKeepsItsBytesAndOtherChoices(final OwnedFailure failure) throws Exception {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder().pkcs12Store(new byte[]{9})
                .storePassword("current-store").keyAlias("current-alias").keyPassword("current-key");
        final Pkcs12Config completed = builder.build();

        failure.assertFailure(path -> builder.pkcs12Store(path), Reader.PKCS12.content());

        assertThat(builder.build()).isEqualTo(completed);
        builder.pkcs12Store(pathOpening(new ByteArrayInputStream(Reader.PKCS12.content()), null));
        assertThat(builder.getPkcs12StoreData()).containsExactly(1, 2, 3);
        assertThat(builder.getStorePassword()).containsExactly("current-store".toCharArray());
        assertThat(builder.getKeyAlias()).isEqualTo("current-alias");
        assertThat(builder.getKeyPassword()).containsExactly("current-key".toCharArray());
        assertThat(completed.getPkcs12StoreData()).containsExactly(9);
    }

    @Test
    void malformedOwnedCertificateKeepsThePreviousChoiceAndItsParsingFailureBeforeCloseFailure() throws Exception {
        final X509Certificate original = originalCertificate();
        final SmimeEncryptionConfig.SmimeEncryptionConfigBuilder builder = SmimeEncryptionConfig.builder().x509Certificate(original);
        final IOException closeFailure = new IOException("key close failed");
        final TrackingInputStream input = new TrackingInputStream(new byte[]{1, 2, 3}, null, closeFailure, null);

        final Throwable failure = catchThrowable(() -> builder.x509Certificate(pathOpening(input, null)));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(CertificateException.class);
        assertThat(failure.getCause().getSuppressed()).containsExactly(closeFailure);
        assertThat(builder.getX509Certificate()).isSameAs(original);
        assertThat(input.closed).isTrue();
    }

    @Test
    void invalidSigningArgumentsKeepThePreviousChoiceAndTheirFailureBeforeCloseFailure() throws Exception {
        final Pkcs12Config original = originalStore();
        final SmimeSigningConfig.SmimeSigningConfigBuilder builder = SmimeSigningConfig.builder().pkcs12Config(original);
        final IOException closeFailure = new IOException("key close failed");
        final TrackingInputStream input = new TrackingInputStream(Reader.SMIME_SIGNING.content(), null, closeFailure, null);

        final Throwable failure = catchThrowable(() -> builder.pkcs12Config(pathOpening(input, null), "next-store", null, "next-key"));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("keyAlias is required");
        assertThat(failure.getSuppressed()).containsExactly(closeFailure);
        assertThat(builder.getPkcs12Config()).isSameAs(original);
        assertThat(input.closed).isTrue();
    }

    private static Pkcs12Config originalStore() {
        return Pkcs12Config.builder().pkcs12Store(new byte[]{9}).storePassword("current-store")
                .keyAlias("current-alias").keyPassword("current-key").build();
    }

    private static X509Certificate originalCertificate() throws Exception {
        try (InputStream input = ManagedKeyMaterialReadTest.class.getResourceAsStream("/managed-key-material-original-certificate.pem")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void assertPreparationFailure(final Throwable failure, final IOException cause) {
        assertThat(failure).isInstanceOf(MailException.class).hasCause(cause);
        final MailException mailFailure = (MailException) failure;
        assertThat(mailFailure.getKind()).isEqualTo(MailFailureKind.PREPARATION);
        assertThat(mailFailure.getSubmissionReceipt()).isEmpty();
    }

    private static Path pathOpening(final InputStream input, final IOException failure) throws IOException {
        final FileSystem fileSystem = new StreamFileSystem(input, failure);
        return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("getFileSystem")) { return fileSystem; }
            if (method.getName().equals("toString")) { return "test key-material path"; }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class StreamFileSystem extends FileSystem {
        private final FileSystemProvider provider;

        private StreamFileSystem(final InputStream input, final IOException failure) {
            provider = new StreamProvider(input, failure);
        }

        @Override public FileSystemProvider provider() { return provider; }
        @Override public void close() { }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String getSeparator() { return "/"; }
        @Override public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }
        @Override public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }
        @Override public Set<String> supportedFileAttributeViews() { throw new UnsupportedOperationException(); }
        @Override public Path getPath(final String first, final String... more) { throw new UnsupportedOperationException(); }
        @Override public PathMatcher getPathMatcher(final String syntaxAndPattern) { throw new UnsupportedOperationException(); }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
        @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
    }

    private static final class StreamProvider extends FileSystemProvider {
        private final InputStream input;
        private final IOException failure;

        private StreamProvider(final InputStream input, final IOException failure) {
            this.input = input;
            this.failure = failure;
        }

        @Override public InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
            if (failure != null) { throw failure; }
            return input;
        }

        @Override public String getScheme() { return "managed-key-material-test"; }
        @Override public FileSystem newFileSystem(final URI uri, final Map<String, ?> environment) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(final URI uri) { throw new UnsupportedOperationException(); }
        @Override public Path getPath(final URI uri) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options,
                final FileAttribute<?>... attributes) { throw new UnsupportedOperationException(); }
        @Override public DirectoryStream<Path> newDirectoryStream(final Path directory,
                final DirectoryStream.Filter<? super Path> filter) { throw new UnsupportedOperationException(); }
        @Override public void createDirectory(final Path directory, final FileAttribute<?>... attributes) { throw new UnsupportedOperationException(); }
        @Override public void delete(final Path path) { throw new UnsupportedOperationException(); }
        @Override public void copy(final Path source, final Path target, final CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public void move(final Path source, final Path target, final CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public boolean isSameFile(final Path first, final Path second) { throw new UnsupportedOperationException(); }
        @Override public boolean isHidden(final Path path) { throw new UnsupportedOperationException(); }
        @Override public FileStore getFileStore(final Path path) { throw new UnsupportedOperationException(); }
        @Override public void checkAccess(final Path path, final AccessMode... modes) { throw new UnsupportedOperationException(); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(final Path path,
                final Class<V> type, final LinkOption... options) { throw new UnsupportedOperationException(); }
        @Override public <A extends BasicFileAttributes> A readAttributes(final Path path,
                final Class<A> type, final LinkOption... options) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> readAttributes(final Path path,
                final String attributes, final LinkOption... options) { throw new UnsupportedOperationException(); }
        @Override public void setAttribute(final Path path, final String attribute,
                final Object value, final LinkOption... options) { throw new UnsupportedOperationException(); }
    }

    private enum OwnedFailure {
        OPEN, READ, CLOSE, RUNTIME;

        private void assertFailure(final Consumer<Path> replacement, final byte[] content) throws Exception {
            final IOException readFailure = this == READ ? new IOException("key read failed") : null;
            final IOException closeFailure = new IOException("key close failed");
            final IllegalStateException runtimeFailure = this == RUNTIME ? new IllegalStateException("application stream failure") : null;
            final TrackingInputStream input = new TrackingInputStream(content, readFailure, closeFailure, runtimeFailure);
            final Path path = pathOpening(input, this == OPEN ? closeFailure : null);

            final Throwable failure = catchThrowable(() -> replacement.accept(path));

            if (this == RUNTIME) {
                assertThat(failure).isSameAs(runtimeFailure);
                assertThat(failure.getSuppressed()).containsExactly(closeFailure);
            } else {
                assertPreparationFailure(failure, this == READ ? readFailure : closeFailure);
                if (this == READ) {
                    assertThat(readFailure.getSuppressed()).containsExactly(closeFailure);
                }
            }
            assertThat(input.closed).isEqualTo(this != OPEN);
        }
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
