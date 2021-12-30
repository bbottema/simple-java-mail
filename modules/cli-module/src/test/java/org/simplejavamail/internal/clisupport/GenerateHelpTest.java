package org.simplejavamail.internal.clisupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class GenerateHelpTest {

	private PrintStream sysOut;
	private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

	@Before
	public void setUpStreams() {
		sysOut = System.out;
		System.setOut(new PrintStream(outContent));
	}

	@After
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
	}

	@Test
	public void testListRootHelpWithoutError() {
		CliSupport.runCLI(new String[]{ "--help" });
	}
}