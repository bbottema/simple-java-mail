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
}