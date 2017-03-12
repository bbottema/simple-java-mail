package org.simplejavamail.internal.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MiscUtilTest {
	@Test
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
		assertThat(MiscUtil.valueNullOrEmpty("")).isEqualTo(true);
		assertThat(MiscUtil.valueNullOrEmpty(null)).isEqualTo(true);
		assertThat(MiscUtil.valueNullOrEmpty("blah")).isEqualTo(false);
		assertThat(MiscUtil.valueNullOrEmpty(2534)).isEqualTo(false);
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
}