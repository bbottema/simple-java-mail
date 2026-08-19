package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.CommandInfo;
import jakarta.activation.CommandMap;
import jakarta.activation.DataContentHandler;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.recipient.RecipientBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarContentHandlerIsolationTest {

	@Test
	void calendarConversionDoesNotReplaceOrRequireTheJvmDefaultCommandMap() throws Exception {
		final CommandMap original = CommandMap.getDefaultCommandMap();
		final CommandMap applicationCommandMap = new EmptyCommandMap();
		CommandMap.setDefaultCommandMap(applicationCommandMap);
		try {
			final Email email = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank()
					.from("sender@example.com")
					.withRecipients(RecipientBuilder.to(null, "receiver@example.com"))
					.withSubject("calendar")
					.withCalendarText(CalendarMethod.REQUEST, "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR")
					.buildEmail();

			final Email roundTripped = EmailConverter.mimeMessageToEmail(
					EmailConverter.emailToMimeMessage(email));

			assertThat(roundTripped.getCalendarText()).isEqualTo(email.getCalendarText());
			assertThat(CommandMap.getDefaultCommandMap()).isSameAs(applicationCommandMap);
		} finally {
			CommandMap.setDefaultCommandMap(original);
		}
	}

	private static final class EmptyCommandMap extends CommandMap {
		@Override
		public CommandInfo[] getPreferredCommands(final String mimeType) {
			return new CommandInfo[0];
		}

		@Override
		public CommandInfo[] getAllCommands(final String mimeType) {
			return new CommandInfo[0];
		}

		@Override
		public CommandInfo getCommand(final String mimeType, final String commandName) {
			return null;
		}

		@Override
		public DataContentHandler createDataContentHandler(final String mimeType) {
			return null;
		}
	}
}
