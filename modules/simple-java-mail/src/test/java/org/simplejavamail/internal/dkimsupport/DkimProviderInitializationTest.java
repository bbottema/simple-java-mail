package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DkimProviderInitializationTest {
    @TempDir Path temporary;

    @Test
    void availabilityLoadingAndRsaDoNotInitializeTheEdProvider() throws Throwable {
        final Provider[] globalProviders = Security.getProviders();
        try (URLClassLoader runtime = runtime()) {
            final Class<?> probe = runtime.loadClass("fixture.ProviderProbe");
            assertThat(invoke(runtime, "available")).isEqualTo(true);
            assertUninitialized(probe);
            invoke(runtime, "load");
            assertUninitialized(probe);
            assertThat((String) invoke(runtime, "sign", "SHA256_WITH_RSA", rsaKey())).contains("rsa-sha256");
            assertUninitialized(probe);

            // The recording provider intentionally has no algorithms; the production Ed path must select it.
            final Throwable edFailure = catchThrowable(() -> invoke(runtime, "sign", "SHA256_WITH_ED25519", edKey()));
            assertThat(edFailure).hasRootCauseInstanceOf(NoSuchAlgorithmException.class)
                    .hasStackTraceContaining("org.simplejavamail.internal.dkimsupport.DKIMSigner.readPrivateKey");
            assertThat(probe.getField("initialized").getInt(null)).isEqualTo(1);
            assertThat(probe.getField("constructed").getInt(null)).isEqualTo(1);
            assertThat(Security.getProviders()).containsExactly(globalProviders);
        }
    }

    @Test
    void providerConstructionFailureIsDeferredAndDoesNotDisableRsa() throws Throwable {
        final Provider[] globalProviders = Security.getProviders();
        try (URLClassLoader runtime = runtime()) {
            final Class<?> probe = runtime.loadClass("fixture.ProviderProbe");
            probe.getField("fail").setBoolean(null, true);
            assertThat(invoke(runtime, "available")).isEqualTo(true);
            assertUninitialized(probe);
            invoke(runtime, "load");
            assertUninitialized(probe);
            final byte[] rsa = rsaKey();
            assertThat((String) invoke(runtime, "sign", "SHA256_WITH_RSA", rsa)).contains("rsa-sha256");
            assertUninitialized(probe);
            final byte[] ed = edKey();
            final Throwable edFailure = catchThrowable(() -> invoke(runtime, "sign", "SHA256_WITH_ED25519", ed));
            assertThat(edFailure).isInstanceOf(ExceptionInInitializerError.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("provider construction failed")
                    .hasStackTraceContaining("org.simplejavamail.internal.dkimsupport.DKIMSigner.readPrivateKey");
            assertThat(probe.getField("constructed").getInt(null)).isEqualTo(1);
            assertThat(catchThrowable(() -> invoke(runtime, "sign", "SHA256_WITH_ED25519", ed)))
                    .isInstanceOf(NoClassDefFoundError.class);
            assertThat(probe.getField("constructed").getInt(null)).isEqualTo(1);
            assertThat((String) invoke(runtime, "sign", "SHA256_WITH_RSA", rsa)).contains("rsa-sha256");
            assertThat(Security.getProviders()).containsExactly(globalProviders);
        }
    }

    @Test
    void absentDkimOrProviderKeepsTheModuleUnavailable() throws Throwable {
        for (final String unavailable : List.of("org.simplejavamail.internal.dkimsupport.DKIMSigner",
                "org.bouncycastle.jce.provider.BouncyCastleProvider")) {
            try (URLClassLoader runtime = runtime(unavailable)) {
                assertThat(invoke(runtime, "available")).as(unavailable).isEqualTo(false);
                final Throwable missing = catchThrowable(() -> invoke(runtime, "load"));
                assertThat(missing).as(unavailable).isNotNull()
                        .hasMessage("DKIM module not found, make sure it is on the classpath "
                                + "(https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module)");
                assertThat(missing.getClass().getName()).isEqualTo("org.simplejavamail.internal.moduleloader.ModuleLoaderException");
            }
        }
    }

    private URLClassLoader runtime() throws Exception {
        return runtime(null);
    }

    private URLClassLoader runtime(final String unavailable) throws Exception {
        final Path probe = temporary.resolve("fixture/ProviderProbe.java");
        final Path provider = temporary.resolve("org/bouncycastle/jce/provider/BouncyCastleProvider.java");
        Files.createDirectories(probe.getParent());
        Files.createDirectories(provider.getParent());
        Files.writeString(probe, "package fixture; public class ProviderProbe { public static int initialized, constructed; public static boolean fail; }");
        Files.writeString(provider, "package org.bouncycastle.jce.provider; public class BouncyCastleProvider extends java.security.Provider { "
                + "static { fixture.ProviderProbe.initialized++; } public BouncyCastleProvider() { super(\"probe\", 1.0, \"test\"); "
                + "fixture.ProviderProbe.constructed++; if (fixture.ProviderProbe.fail) throw new IllegalStateException(\"provider construction failed\"); } }");
        assertThat(ToolProvider.getSystemJavaCompiler()).as("tests run with a JDK").isNotNull();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temporary.toString(), probe.toString(), provider.toString())).isZero();
        final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final List<URL> urls = new ArrayList<>();
        urls.add(temporary.toUri().toURL());
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) urls.add(new File(entry).toURI().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals(unavailable)) throw new ClassNotFoundException("Deliberately unavailable: " + name);
                return super.loadClass(name, resolve);
            }
        };
    }

    private static Object invoke(final ClassLoader runtime, final String method, final Object... args) throws Throwable {
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(runtime);
        try {
            final Class<?> driver = runtime.loadClass(Driver.class.getName());
            if ("sign".equals(method)) return driver.getMethod(method, String.class, byte[].class).invoke(null, args);
            return driver.getMethod(method).invoke(null);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void assertUninitialized(final Class<?> probe) throws Exception {
        assertThat(probe.getField("initialized").getInt(null)).isZero();
        assertThat(probe.getField("constructed").getInt(null)).isZero();
    }

    private static byte[] rsaKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair().getPrivate().getEncoded();
    }

    private static byte[] edKey() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider()).generateKeyPair().getPrivate().getEncoded();
    }

    /** Runs inside the isolated application loader, so production types and optional modules share its boundary. */
    public static final class Driver {
        public static boolean available() { return ModuleLoader.dkimModuleAvailable(); }
        public static void load() { ModuleLoader.loadDKIMModule(); }
        public static String sign(final String algorithm, final byte[] privateKey) throws Exception {
            final String address = "sender@supersecret-testing-domain.com";
            final Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().from(address)
                    .to("recipient@example.test").withPlainText("body").buildEmail();
            final String wire = "From: " + address + "\r\nTo: recipient@example.test\r\nSubject: fixture\r\n\r\nbody\r\n";
            final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
            final DkimConfig config = DkimConfig.builder().dkimPrivateKeyData(privateKey)
                    .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("test").signingAlgorithm(algorithm).build();
            return Arrays.toString(ModuleLoader.loadDKIMModule().signMessageWithDKIM(email, message, config,
                    new Recipient(null, address, null, null)).getHeader("DKIM-Signature"));
        }
    }
}
