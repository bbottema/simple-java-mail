package testutil;

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.simplejavamail.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;

public class SecureTestDataHelper {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources";

	public static void runTestWithSecureTestData(PasswordsConsumer consumer)
			throws Exception {
		try {
			consumer.accept(accessSecureTestData());
		} finally {
			cleanupSecureTestData();
		}
	}

	private static Properties accessSecureTestData()
			throws IOException {
		final InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream("secure-testdata-passwords.properties");
		final Properties passwords = new Properties();
		passwords.load(checkArgumentNotEmpty(inputStream, "InputStream was null"));

		assumeThat(passwords.getProperty("legacy-signed-enveloped-email-zip"))
				.as("secure-testdata-passwords.properties")
				.isNotEmpty();

		final String secureDataPassword = passwords.getProperty("legacy-signed-enveloped-email-zip");
		new ZipFile(RESOURCES + "/secure-testdata/legacy-signed-enveloped-email.zip", secureDataPassword.toCharArray())
				.extractAll(RESOURCES + "/secure-testdata/legacy-signed-enveloped-email");
		new ZipFile(RESOURCES + "/secure-testdata/legacy-signed-enveloped-email/file-hider.zip", secureDataPassword.toCharArray())
				.extractAll(RESOURCES + "/secure-testdata/legacy-signed-enveloped-email");

		return passwords;
	}

	private static void cleanupSecureTestData() {
		final File file = new File(RESOURCES + "/secure-testdata/legacy-signed-enveloped-email");

		while (file.exists()) {
			try {
				FileUtils.deleteDirectory(file);
			} catch (IOException e) {
				try {
					//noinspection BusyWait
					Thread.sleep(100);
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	public interface PasswordsConsumer {
		void accept(Properties passwords);
	}
}
