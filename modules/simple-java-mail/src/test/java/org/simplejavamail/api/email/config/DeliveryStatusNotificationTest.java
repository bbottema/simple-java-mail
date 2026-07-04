package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.FULL_MESSAGE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

public class DeliveryStatusNotificationTest {

	@Test
	public void of() {
		assertThat(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE, DELAY))
				.isEqualTo(DeliveryStatusNotification.builder()
						.returnOption(HEADERS_ONLY)
						.notifyOptions(FAILURE, DELAY)
						.build());
	}

	@Test
	public void of_NoDeliveryStatusOptions() {
		assertThatThrownBy(() -> DeliveryStatusNotification.of((DeliveryStatusNotification.ReturnOption) null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("At least one delivery status notification option");
	}

	@Test
	public void of_NeverCannotBeCombinedWithOtherNotifyOptions() {
		assertThatThrownBy(() -> DeliveryStatusNotification.of(FULL_MESSAGE, NEVER, FAILURE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("NEVER cannot be combined");
	}

	@Test
	public void parseNotifyOptions() {
		assertThat(DeliveryStatusNotification.parseNotifyOptions("failure, delay;SUCCESS"))
				.containsExactly(FAILURE, DELAY, SUCCESS);
		assertThat(DeliveryStatusNotification.parseNotifyOptions("never"))
				.containsExactly(NEVER);
	}

	@Test
	public void parseReturnOption() {
		assertThat(DeliveryStatusNotification.parseReturnOption("headers-only")).isEqualTo(HEADERS_ONLY);
		assertThat(DeliveryStatusNotification.parseReturnOption("FULL")).isEqualTo(FULL_MESSAGE);
	}
}
