package org.simplejavamail.mailer.internal.mailsender;

import net.markenwerk.utils.mail.dkim.DkimMessage;
import org.junit.Test;
import org.simplejavamail.email.Email;
import testutil.EmailHelper;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static org.assertj.core.api.Assertions.assertThat;

public class MimeMessageHelperTest {

	@Test
	public void testDKIMPriming()
			throws IOException, MessagingException {
		final Email email = EmailHelper.createDummyEmail();

		// System.out.println(printBase64Binary(Files.readAllBytes(Paths.get("D:\\keys\\dkim.der")))); // needs jdk 1.7
		String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";

		email.signWithDomainKey(new ByteArrayInputStream(parseBase64Binary(privateDERkeyBase64)), "somemail.com", "select");
		MimeMessage mimeMessage = MimeMessageHelper.produceMimeMessage(email);
		// success, signing did not produce an error
		assertThat(mimeMessage).isInstanceOf(DkimMessage.class);
	}

	@Test
	public void testParser()
			throws Exception {
		final Email emailNormal = EmailHelper.createDummyEmail();

		// let's try producing and then consuming a MimeMessage ->
		final MimeMessage mimeMessage = MimeMessageHelper.produceMimeMessage(emailNormal);
		final Email emailFromMimeMessage = new Email(mimeMessage);

		assertThat(emailFromMimeMessage).isEqualTo(emailNormal);
	}
}