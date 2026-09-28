package org.simplejavamail.internal.util;

import jakarta.mail.Session;
import jakarta.mail.util.StreamProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Properties;
import java.util.ServiceConfigurationError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class JakartaMailImplementationTest {
    @Test
    void sessionConstructionDoesNotAddAnAvailabilityLookup() {
        final Properties properties = new Properties();
        final Session session = mock(Session.class);
        try (MockedStatic<Session> sessions = mockStatic(Session.class);
             MockedStatic<StreamProvider> providers = mockStatic(StreamProvider.class)) {
            sessions.when(() -> Session.getInstance(properties)).thenReturn(session);
            assertThat(JakartaMailImplementation.createSession(properties)).isSameAs(session);
            sessions.verify(() -> Session.getInstance(properties));
            providers.verifyNoInteractions();
        }
    }

    @Test
    void providerFailuresKeepTheirCauseAndActionableDiagnosticWithoutCachingTheFailure() {
        final Properties properties = new Properties();
        for (final Throwable failure : new Throwable[]{new IllegalStateException("missing"), new LinkageError("linkage"),
                new ServiceConfigurationError("bad registration")}) {
            final Session recovered = mock(Session.class);
            try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
                sessions.when(() -> Session.getInstance(properties)).thenThrow(failure).thenReturn(recovered);
                assertThatThrownBy(() -> JakartaMailImplementation.createSession(properties))
                        .isInstanceOf(IllegalStateException.class).hasCause(failure)
                        .hasMessageContaining("needs a Jakarta Mail implementation").hasMessageContaining("angus-mail-provider-module");
                assertThat(JakartaMailImplementation.createSession(properties)).isSameAs(recovered);
            }
        }
    }
}
