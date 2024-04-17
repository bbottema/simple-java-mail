package org.simplejavamail.converter.internal.mimemessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.MiscUtil;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

public class MimeMessageHelper2Test {
	
	@BeforeEach
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ModuleLoader._forceRecheckModule();
	}

	@Test
	public void testSignMessageWithDKIM_ShouldFailSpecificallyBecauseDKIMLibraryIsMissing()
			throws ClassNotFoundException {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("dummykey")
						.dkimSigningDomain("moo.com")
						.dkimSelector("selector")
						.build())
				.buildEmail();

		try (MockedStatic<MiscUtil> miscUtilMockedStatic = mockStatic(MiscUtil.class)) {
			miscUtilMockedStatic.when(() -> MiscUtil.classAvailable("org.simplejavamail.internal.dkimsupport.DKIMSigner")).thenReturn(false);

			assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
					.hasMessage("DKIM module not found, make sure it is on the classpath (https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module)");
		}

		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
				.isInstanceOf(Class.forName("org.simplejavamail.internal.dkimsupport.DKIMSigningException"))
				.hasMessage("Error signing MimeMessage with DKIM");
	}
}