package org.simplejavamail.internal.util;

import org.junit.Test;
import org.simplejavamail.api.email.Recipient;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

public class MiscUtilTest {
	@Test
	@SuppressWarnings("ObviousNullCheck")
	public void checkNotNull() {
		assertThat(MiscUtil.checkNotNull("", null)).isEqualTo("");
		assertThat(MiscUtil.checkNotNull("blah", null)).isEqualTo("blah");
		assertThat(MiscUtil.checkNotNull(23523, null)).isEqualTo(23523);
	}
	
	@Test(expected = NullPointerException.class)
	public void checkNotNullWithException() {
		MiscUtil.checkNotNull(null, null);
	}
	
	@Test
	public void checkArgumentNotEmpty() {
		assertThat(MiscUtil.checkArgumentNotEmpty("blah", null)).isEqualTo("blah");
		assertThat(MiscUtil.checkArgumentNotEmpty(234, null)).isEqualTo(234);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void checkArgumentNotEmptyWithEmptyString() {
		MiscUtil.checkArgumentNotEmpty("", null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void checkArgumentNotEmptyWithNullString() {
		MiscUtil.checkArgumentNotEmpty(null, null);
	}
	
	@Test
	public void valueNullOrEmpty() {
		assertThat(MiscUtil.valueNullOrEmpty("")).isTrue();
		assertThat(MiscUtil.valueNullOrEmpty(null)).isTrue();
		assertThat(MiscUtil.valueNullOrEmpty("blah")).isFalse();
		assertThat(MiscUtil.valueNullOrEmpty(2534)).isFalse();
		assertThat(MiscUtil.valueNullOrEmpty(new ArrayList<>())).isTrue();
	}

	@Test
	public void testBuildLogString() {
		assertThat(MiscUtil.buildLogStringForSOCKSCommunication(new byte[] { 1, 2, 3 }, true)).isEqualTo("Received: 1 2 3 ");
		assertThat(MiscUtil.buildLogStringForSOCKSCommunication(new byte[] { 32, 121, 101 }, false)).isEqualTo("Sent: 20 79 65 ");
	}

	@Test
	public void testToInt() {
		assertThat(MiscUtil.toInt((byte) -1)).isEqualTo(255);
		assertThat(MiscUtil.toInt((byte) 0)).isEqualTo(0);
		assertThat(MiscUtil.toInt((byte) 1)).isEqualTo(1);
		assertThat(MiscUtil.toInt((byte) 10)).isEqualTo(10);
		assertThat(MiscUtil.toInt((byte) 100)).isEqualTo(100);
		assertThat(MiscUtil.toInt((byte) -100)).isEqualTo(156);
		assertThat(MiscUtil.toInt((byte) -10)).isEqualTo(246);
	}

	@Test
	public void testEncodeText() {
		assertThat(MiscUtil.encodeText(null)).isNull();
		assertThat(MiscUtil.encodeText("moo moo")).isEqualTo("moo moo");
		assertThat(MiscUtil.encodeText("<html><body>moo</body></html>")).isEqualTo("<html><body>moo</body></html>");
		assertThat(MiscUtil.encodeText("moo moo\u0207")).isEqualTo("=?UTF-8?B?bW9vIG1vb8iH?=");
		assertThat(MiscUtil.encodeText("<html><body>\u0207</body></html>")).isEqualTo("=?UTF-8?B?PGh0bWw+PGJvZHk+yIc8L2JvZHk+PC9odG1sPg==?=");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testExtractEmailAddresses_MissingAddress() {
		MiscUtil.extractEmailAddresses(null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void testExtractEmailAddresses_EmptyAddress() {
		MiscUtil.extractEmailAddresses("");
	}
	
	@Test
	public void testExtractEmailAddresses_SingleAddress() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a@b.com");
		assertThat(singleAddressList).hasSize(1);
		assertThat(singleAddressList).contains("a@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesWithCommas() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com,a2@b.com,a3@b.com");
		assertThat(singleAddressList).hasSize(3);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesWithSemicolons() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com;a2@b.com;a3@b.com");
		assertThat(singleAddressList).hasSize(3);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesMixedCommasAndSemicolons() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com,a2@b.com;a3@b.com;a4@b.com,a5@b.com");
		assertThat(singleAddressList).hasSize(5);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com", "a4@b.com", "a5@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesTralingSpaces() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com, a2@b.com ;a3@b.com;a4@b.com , a5@b.com,a6@b.com");
		assertThat(singleAddressList).hasSize(6);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com", "a4@b.com", "a5@b.com", "a6@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses() {
		String testInput = "name@domain.com,Sixpack, \"Joe 1\" <name@domain.com>, Sixpack, Joe 2 <name@domain.com> ;Sixpack, Joe, 3<name@domain" +
				".com> , nameFoo@domain.com,nameBar@domain.com;nameBaz@domain.com; \" Joe Sixpack 4 \"  <name@domain.com>;";
		assertThat(MiscUtil.extractEmailAddresses(testInput)).containsExactlyInAnyOrder(
				"name@domain.com",
				"Sixpack, \"Joe 1\" <name@domain.com>",
				"Sixpack, Joe 2 <name@domain.com>",
				"Sixpack, Joe, 3<name@domain.com>",
				"nameFoo@domain.com",
				"nameBar@domain.com",
				"nameBaz@domain.com",
				"\" Joe Sixpack 4 \"  <name@domain.com>"
		);
	}
	
	@Test
	public void testAddRecipientByInternetAddress() {
		assertThat(MiscUtil.interpretRecipient(null, false, "a@b.com", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " a@b.com ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " <a@b.com> ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " < a@b.com > ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, "moo <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, "moo<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " moo< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, "\"moo\" <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, "\"moo\"<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " \"moo\"< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(MiscUtil.interpretRecipient(null, false, " \"  m oo  \"< a@b.com   > ", null)).isEqualTo(new Recipient("  m oo  ", "a@b.com", null));
		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThat(MiscUtil.interpretRecipient(null, false, " \"  m oo  \" a@b.com    ", null)).isEqualTo(new Recipient(null, " \"  m oo  \" a@b.com    ", null));
	}
}