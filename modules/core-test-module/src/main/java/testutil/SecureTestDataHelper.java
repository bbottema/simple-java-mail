package testutil;

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.simplejavamail.config.ConfigLoader;

import java.io.File;
import java.io.FileNotFoundException;
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

		assumeThat(passwords.getProperty("secure-testdata-zip"))
				.as("secure-testdata-passwords.properties")
				.isNotEmpty();

		final String secureDataPassword = passwords.getProperty("secure-testdata-zip");
		new ZipFile(RESOURCES + "/secure-testdata/secure-testdata.zip", secureDataPassword.toCharArray())
				.extractAll(RESOURCES + "/secure-testdata/secure-testdata");
		new ZipFile(RESOURCES + "/secure-testdata/secure-testdata/file-hider.zip", secureDataPassword.toCharArray())
				.extractAll(RESOURCES + "/secure-testdata/secure-testdata");

		return passwords;
	}

	private static void cleanupSecureTestData() {
		final File file = new File(RESOURCES + "/secure-testdata/secure-testdata");

		int tries = 0;
		while (file.exists() && tries++ <= 10) {
			try {
				FileUtils.deleteDirectory(file);
			} catch (IOException e) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	public interface PasswordsConsumer {
		void accept(Properties passwords)
				throws FileNotFoundException;
	}
}
