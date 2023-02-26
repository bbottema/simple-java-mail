package org.simplejavamail.converter.internal.mimemessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.BDDMockito;
import org.mockito.internal.stubbing.answers.CallsRealMethods;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.MiscUtil;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;

@RunWith(PowerMockRunner.class)
@PrepareForTest(MiscUtil.class)
@PowerMockIgnore("javax.management.*")
public class MimeMessageHelper2Test {
	
	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ModuleLoader._forceRecheckModule();
	}

	@Test
	public void testSignMessageWithDKIM_ShouldFailSpecificallyBecauseDKIMLibraryIsMissing()
			throws IOException, ClassNotFoundException {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("dummykey")
						.dkimSigningDomain("moo.com")
						.dkimSelector("selector")
						.build())
				.buildEmail();

		PowerMockito.mockStatic(MiscUtil.class, new CallsRealMethods());
		BDDMockito.given(MiscUtil.classAvailable("org.simplejavamail.internal.dkimsupport.DKIMSigner")).willReturn(false);

		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
				.hasMessage("DKIM module not found, make sure it is on the classpath (https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module)");

		PowerMockito.mockStatic(MiscUtil.class, new CallsRealMethods());

		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
				.isInstanceOf(Class.forName("org.simplejavamail.internal.dkimsupport.DKIMSigningException"))
				.hasMessage("Error signing MimeMessage with DKIM");
	}
}