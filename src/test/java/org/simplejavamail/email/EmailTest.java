package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import testutil.ConfigLoaderTestHelper;

import javax.mail.Message;
import java.util.ArrayList;

import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;

public class EmailTest {
	private Email email;
	
	@Before
	public void setup() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		email = new Email();
	}
	
	@Test
	public void testAddRecipient_Basic_Named() {
		email.addRecipient("name1", "1@domain.com", TO);
		email.addRecipient("name2", "2@domain.com,3@domain.com", CC);
		email.addRecipient("name3", "4@domain.com;5@domain.com", BCC);
		email.addRecipient("name4", "6@domain.com;7@domain.com,8@domain.com", TO);
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3", "5@domain.com", BCC),
				new Recipient("name4", "6@domain.com", TO),
				new Recipient("name4", "7@domain.com", TO),
				new Recipient("name4", "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipient_Complex_Named() {
		email.addRecipient("name1", "name1b <1@domain.com>", TO);
		email.addRecipient("name2", "name2b <2@domain.com>,3@domain.com", CC);
		email.addRecipient("name3", "4@domain.com;name3b <5@domain.com>", BCC);
		email.addRecipient("name4", "name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>", TO);
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3b", "5@domain.com", BCC),
				new Recipient("name4b", "6@domain.com", TO),
				new Recipient("name5b", "7@domain.com", TO),
				new Recipient("name6b", "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipients_Basic_Named() {
		email.addRecipients("name1", "1@domain.com", TO);
		email.addRecipients("name2", "2@domain.com,3@domain.com", CC);
		email.addRecipients("name3", "4@domain.com;5@domain.com", BCC);
		email.addRecipients("name4", "6@domain.com;7@domain.com,8@domain.com", TO);
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3", "5@domain.com", BCC),
				new Recipient("name4", "6@domain.com", TO),
				new Recipient("name4", "7@domain.com", TO),
				new Recipient("name4", "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipients_Complex_Named() {
		email.addRecipients("name1", "name1b <1@domain.com>", TO);
		email.addRecipients("name2", "name2b <2@domain.com>,3@domain.com", CC);
		email.addRecipients("name3", "4@domain.com;name3b <5@domain.com>", BCC);
		email.addRecipients("name4", "name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>", TO);
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3b", "5@domain.com", BCC),
				new Recipient("name4b", "6@domain.com", TO),
				new Recipient("name5b", "7@domain.com", TO),
				new Recipient("name6b", "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Basic_Named() {
		email.addRecipients("name1", TO, "1@domain.com");
		email.addRecipients("name2", CC, "2@domain.com", "3@domain.com");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Complex_Named() {
		email.addRecipients("name1", TO, "name1b <1@domain.com>");
		email.addRecipients("name2", CC, "name2b <2@domain.com>", "name3b <3@domain.com>");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Basic_Nameless() {
		email.addRecipients(TO, "1@domain.com");
		email.addRecipients(CC, "2@domain.com", "3@domain.com");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "1@domain.com", TO),
				new Recipient(null, "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Complex_Nameless() {
		email.addRecipients(TO, "name1b <1@domain.com>");
		email.addRecipients(CC, "name2b <2@domain.com>", "name3b <3@domain.com>");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipients_Basic_Nameless() {
		email.addRecipients(TO, "1@domain.com");
		email.addRecipients(CC, "2@domain.com,3@domain.com");
		email.addRecipients(BCC, "4@domain.com;5@domain.com");
		email.addRecipients(TO, "6@domain.com;7@domain.com,8@domain.com");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "1@domain.com", TO),
				new Recipient(null, "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC),
				new Recipient(null, "4@domain.com", BCC),
				new Recipient(null, "5@domain.com", BCC),
				new Recipient(null, "6@domain.com", TO),
				new Recipient(null, "7@domain.com", TO),
				new Recipient(null, "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipients_Complex_Nameless() {
		email.addRecipients(TO, "name1b <1@domain.com>");
		email.addRecipients(CC, "name2b <2@domain.com>,3@domain.com");
		email.addRecipients(BCC, "4@domain.com;name3b <5@domain.com>");
		email.addRecipients(TO, "name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC),
				new Recipient(null, "4@domain.com", BCC),
				new Recipient("name3b", "5@domain.com", BCC),
				new Recipient("name4b", "6@domain.com", TO),
				new Recipient("name5b", "7@domain.com", TO),
				new Recipient("name6b", "8@domain.com", TO)
		);
	}
	
	@Test
	public void testAddRecipients_Complex_Quicktest() {
		// accept valid addresses:
		email.addRecipients(TO, "Abc\\@def@example.com");
		email.addRecipients(TO, "Fred\\ Bloggs@example.com");
		email.addRecipients(TO, "Joe.\\\\Blow@example.com");
		email.addRecipients(TO, "\"Abc@def\"@example.com");
		email.addRecipients(TO, "\"Fred Bloggs\"@example.com");
		email.addRecipients(TO, "customer/department=shipping@example.com");
		email.addRecipients(TO, "$A12345@example.com");
		email.addRecipients(TO, "!def!xyz%abc@example.com");
		email.addRecipients(TO, "_somename@example.com");
		email.addRecipients(TO, "very.“():[]”.VERY.“very@\\\\ \"very”.unusual@strange.example.com");
		
		// even accept invalid addresses:
		email.addRecipients(TO, "Name <1@domai@n.com>");
		
		// OK, InternetAddress#parse() didn't error out on these addresses
	}
	
	@Test
	public void testAddRecipientByInternetAddress(){
		ArrayList<Recipient> recipients = new ArrayList<>();
		assertThat(parsedEmail(null, "a@b.com", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(parsedEmail(null, " a@b.com ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(parsedEmail(null, " <a@b.com> ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(parsedEmail(null, " < a@b.com > ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(parsedEmail(null, "moo <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, "moo<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, " moo< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, "\"moo\" <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, "\"moo\"<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, " \"moo\"< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(parsedEmail(null, " \"  m oo  \"< a@b.com   > ", null)).isEqualTo(new Recipient("  m oo  ", "a@b.com", null));
		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThat(parsedEmail(null, " \"  m oo  \" a@b.com    ", null)).isEqualTo(new Recipient(null, " \"  m oo  \" a@b.com    ", null));
	}
	
	private Recipient parsedEmail(String name, String address, Message.RecipientType type) {
		ArrayList<Recipient> recipients = new ArrayList<>();
		Email.addRecipientByInternetAddress(recipients, name, address, type);
		assertThat(recipients).hasSize(1);
		return recipients.get(0);
	}
}