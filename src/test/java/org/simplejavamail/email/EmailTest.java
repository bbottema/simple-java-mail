package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import testutil.ConfigLoaderTestHelper;

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
	public void testAddRecipients_Basic_Named() {
		email.addNamedToRecipients("name1", "1@domain.com");
		email.addNamedCcRecipients("name2", "2@domain.com,3@domain.com");
		email.addNamedBccRecipients("name3", "4@domain.com;5@domain.com");
		email.addNamedToRecipients("name4", "6@domain.com;7@domain.com,8@domain.com");
		
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
		email.addNamedToRecipients("name1", "name1b <1@domain.com>");
		email.addNamedCcRecipients("name2", "name2b <2@domain.com>,3@domain.com");
		email.addNamedBccRecipients("name3", "4@domain.com;name3b <5@domain.com>");
		email.addNamedToRecipients("name4", "name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>");
		
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
		email.addNamedToRecipients("name1", "1@domain.com");
		email.addNamedCcRecipients("name2", "2@domain.com", "3@domain.com");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Complex_Named() {
		email.addNamedToRecipients("name1", "name1b <1@domain.com>");
		email.addNamedCcRecipients("name2", "name2b <2@domain.com>", "name3b <3@domain.com>");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Basic_Nameless() {
		email.addToRecipients("1@domain.com");
		email.addCcRecipients("2@domain.com", "3@domain.com");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "1@domain.com", TO),
				new Recipient(null, "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipientsVarArgs_Complex_Nameless() {
		email.addToRecipients("name1b <1@domain.com>");
		email.addCcRecipients("name2b <2@domain.com>", "name3b <3@domain.com>");
		
		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}
	
	@Test
	public void testAddRecipients_Basic_Nameless() {
		email.addToRecipients("1@domain.com");
		email.addCcRecipients("2@domain.com,3@domain.com");
		email.addBccRecipients("4@domain.com;5@domain.com");
		email.addToRecipients("6@domain.com;7@domain.com,8@domain.com");
		
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
		email.addToRecipients("name1b <1@domain.com>");
		email.addCcRecipients("name2b <2@domain.com>,3@domain.com");
		email.addBccRecipients("4@domain.com;name3b <5@domain.com>");
		email.addToRecipients("name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>");
		
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
		email.addToRecipients("Abc\\@def@example.com");
		email.addToRecipients("Fred\\ Bloggs@example.com");
		email.addToRecipients("Joe.\\\\Blow@example.com");
		email.addToRecipients("\"Abc@def\"@example.com");
		email.addToRecipients("\"Fred Bloggs\"@example.com");
		email.addToRecipients("customer/department=shipping@example.com");
		email.addToRecipients("$A12345@example.com");
		email.addToRecipients("!def!xyz%abc@example.com");
		email.addToRecipients("_somename@example.com");
		email.addToRecipients("very.“():[]”.VERY.“very@\\\\ \"very”.unusual@strange.example.com");
		
		// even accept invalid addresses:
		email.addToRecipients("Name <1@domai@n.com>");
		
		// OK, InternetAddress#parse() didn't error out on these addresses
	}
	
	@Test
	public void testAddRecipientByInternetAddress() {
		assertThat(Email.interpretRecipientData(null, "a@b.com", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " a@b.com ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " <a@b.com> ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " < a@b.com > ", null)).isEqualTo(new Recipient(null, "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, "moo <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, "moo<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " moo< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, "\"moo\" <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, "\"moo\"<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " \"moo\"< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null));
		assertThat(Email.interpretRecipientData(null, " \"  m oo  \"< a@b.com   > ", null)).isEqualTo(new Recipient("  m oo  ", "a@b.com", null));
		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThat(Email.interpretRecipientData(null, " \"  m oo  \" a@b.com    ", null)).isEqualTo(new Recipient(null, " \"  m oo  \" a@b.com    ", null));
	}
}