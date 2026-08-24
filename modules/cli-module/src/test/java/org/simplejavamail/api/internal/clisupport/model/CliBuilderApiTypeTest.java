package org.simplejavamail.api.internal.clisupport.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.MAILER;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeOptionsInText;

public class CliBuilderApiTypeTest {
	
	@Test
	public void findInSynopsis() {
		String testStr = colorizeOptionsInText("\tsend [--help -h, --version -V] --moo:options --options", "yellow");
		assertThat(CliBuilderApiType.findForCliSynopsis(testStr)).isEmpty();
		
		testStr = colorizeOptionsInText("\tsend [--help -h, --version -V] --email:options --mailer:options", "yellow");
		assertThat(CliBuilderApiType.findForCliSynopsis(testStr)).containsExactlyInAnyOrder(EMAIL, MAILER);
		
		testStr = colorizeOptionsInText("\tconvert [--help -h, --version -V] --email:options", "yellow");
		assertThat(CliBuilderApiType.findForCliSynopsis(testStr)).containsExactlyInAnyOrder(EMAIL);
		
		testStr = colorizeOptionsInText("\tconnect [--help -h, --version -V] --mailer:options", "yellow");
		assertThat(CliBuilderApiType.findForCliSynopsis(testStr)).containsExactlyInAnyOrder(MAILER);
	}
}
