package testutil.testrules;

import org.jetbrains.annotations.NotNull;

/**
 * SmtpServerSupport - Interface usually implemented by the JUnit test class.
 */
public interface SmtpServerSupport {
	/**
	 * the SMTP port.
	 */
	int getPort();

	/**
	 * The hostname (for example 'localhost')
	 *
	 * @return a {@link String}
	 */
	@NotNull
	String getHostname();
}