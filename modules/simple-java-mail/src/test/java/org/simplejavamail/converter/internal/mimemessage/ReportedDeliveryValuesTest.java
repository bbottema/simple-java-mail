package org.simplejavamail.converter.internal.mimemessage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportedDeliveryValuesTest {

	@Test
	void statusAssemblyRejectsOutOfRangeComponentsAndAcceptsTheBoundaries() {
		for (final int statusClass : new int[]{-1, 0, 1, 3, 6, 9}) {
			assertThatThrownBy(() -> EnhancedStatusCodeImpl.builder().statusClass(statusClass).subject(1).detail(1).build())
					.isInstanceOf(IllegalArgumentException.class);
		}
		for (final int invalid : new int[]{-1, 1000}) {
			assertThatThrownBy(() -> EnhancedStatusCodeImpl.builder().statusClass(5).subject(invalid).detail(1).build())
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> EnhancedStatusCodeImpl.builder().statusClass(5).subject(1).detail(invalid).build())
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(EnhancedStatusCodeImpl.builder().statusClass(2).subject(0).detail(0).build().toString()).isEqualTo("2.0.0");
		assertThat(EnhancedStatusCodeImpl.builder().statusClass(5).subject(999).detail(999).build().toString()).isEqualTo("5.999.999");
	}

	@Test
	void assemblyRequiresBothPartsOfTypedFacts() {
		assertThatThrownBy(() -> ReportedRecipientImpl.builder().type("rfc822").build()).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ReportedRecipientImpl.builder().address("reader@example.test").build()).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ReportedMailServerImpl.builder().type("dns").build()).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ReportedMailServerImpl.builder().name("relay.example.test").build()).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> DeliveryDiagnosticImpl.builder().type("smtp").build()).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> DeliveryDiagnosticImpl.builder().text("550 unavailable").build()).isInstanceOf(NullPointerException.class);
	}

	@Test
	void derivedViewsDoNotTurnValueEqualityIntoProviderObjectIdentity() {
		final var first = DeliveryDiagnosticImpl.builder().type("smtp").text("550 5.1.1 No mailbox").build();
		final var second = DeliveryDiagnosticImpl.builder().type("smtp").text("550 5.1.1 No mailbox").build();
		assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
		assertThat(first.getSmtpResponse()).isNotSameAs(second.getSmtpResponse());
		assertThat(first.getSmtpResponse()).isSameAs(first.getSmtpResponse());
		final var recipient = ReportedRecipientImpl.builder().type("rfc822").address("Reader@Example.test").build();
		assertThat(recipient).isEqualTo(ReportedRecipientImpl.builder().type("rfc822").address("Reader@Example.test").build());
	}

	@Test
	void smtpViewsPreserveIntermediateRepliesWithoutCallingThemAcceptance() {
		final var diagnostic = DeliveryDiagnosticImpl.builder().type("smtp").text("354 Send content").build();
		assertThat(diagnostic.getSmtpResponse().getReturnCode()).isEqualTo(354);
		assertThat(diagnostic.getSmtpResponse().isPositiveCompletionReply()).isFalse();
	}

	@Test
	void reportValueInstancesHaveOnlyFinalStateAndNoPublicConstruction() {
		for (final Class<?> type : List.of(EnhancedStatusCodeImpl.class, ReportedRecipientImpl.class, ReportedMailServerImpl.class,
				DeliveryDiagnosticImpl.class)) {
			assertThat(Modifier.isPublic(type.getModifiers())).isFalse();
			assertThat(type.getConstructors()).isEmpty();
			assertThat(Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers())))
					.allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
		}
	}
}
