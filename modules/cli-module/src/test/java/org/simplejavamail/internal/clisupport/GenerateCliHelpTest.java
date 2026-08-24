package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class GenerateCliHelpTest {

	private PrintStream sysOut;
	private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

	@BeforeEach
	public void setUpStreams() {
		System.out.println("GenerateCliHelpTest.setUpStreams(): DISABLING System.out during CLI debug logging");
		sysOut = System.out;
		System.setOut(new PrintStream(outContent));
	}

	@AfterEach
	public void revertStreams() {
		System.setOut(sysOut);
	}

	@Test
	public void testListAllUsagesWithoutError() {
		CliSupport.listUsagesForAllOptions();
	}

	@Test
	public void testListHelpForSendWithoutError() {
		CliSupport.runCLI(new String[]{"send", "--help"});

		assertThat(new String(outContent.toByteArray(), UTF_8))
				.contains("https://www.simplejavamail.org/cli.html")
				.doesNotContain("https://www.simplejavamail.org/#/cli");
	}

	@Test
	public void testUsagesThatContainsPercentS() {
		CliSupport.runCLI(new String[] {"send", "--email:replyingTo--help",});
	}

	@Test
	public void testListHelpForConnectWithoutError() {
		CliSupport.runCLI(new String[]{"connect", "--help"});
	}

	@Test
	public void testListHelpForValidateWithoutError() {
		CliSupport.runCLI(new String[]{"validate", "--help"});

		assertThat(new String(outContent.toByteArray(), UTF_8))
				.contains("--email:options --mailer:options")
				.contains("without connecting to SMTP");
	}

	@Test
	public void testListHelpForForwardingWithoutError() {
		CliSupport.runCLI(new String[] {"send", "--email:forwarding--help",});
	}

	@Test
	public void testListHelpForClearProxyWithoutError() {
		CliSupport.runCLI(new String[] {"send", "--mailer:clearProxy--help",});
	}

	@Test
	public void testListHelpForAsyncWithoutError() {
		CliSupport.runCLI(new String[] {"send", "--mailer:async--help",});

		assertThat(new String(outContent.toByteArray(), UTF_8))
				.contains("https://www.simplejavamail.org/cli.html")
				.doesNotContain("https://www.simplejavamail.org/#/cli");
	}

	@Test
	public void resetDisableAllClientValidationsHelpReportsBlockingDefault() {
		CliSupport.runCLI(new String[] {"send", "--mailer:resetDisableAllClientValidations--help"});

		assertThat(new String(outContent.toByteArray(), UTF_8))
				.contains("Restores blocking client-side validation")
				.contains("default (false)")
				.doesNotContain("default (true)");
	}

	@Test
	public void testListRootHelpWithoutError() {
		CliSupport.runCLI(new String[]{ "--help" });
	}
}
