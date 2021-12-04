package org.simplejavamail.internal.util;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FileUtilTest {

	@Test
	public void testReadFileContent()
			throws IOException {
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			@Override
			public void call()
					throws Throwable {
				FileUtil.readFileContent(new File("moo"));
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("File not found: moo");

		assertThat(FileUtil.readFileContent(new File("src/test/resources/ignore.properties"))).contains("simplejavamail.defaults.bcc.address=moo");
	}
}