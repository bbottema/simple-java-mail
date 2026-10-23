package org.simplejavamail.examples.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExampleHttpTest {
    @Test
    void evidenceFetchClosesItsClientAfterSuccess() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1, 2, 3});
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        try (var factory = mockStatic(HttpClient.class)) {
            factory.when(HttpClient::newHttpClient).thenReturn(client);
            assertArrayEquals(new byte[]{1, 2, 3}, ExampleHttp.getBytes("http://fixture.example.test/proof"));
        }

        verify(client).close();
    }

    @Test
    void evidenceFetchClosesItsClientWithoutReplacingTheRequestFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var failure = new IOException("unavailable");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(failure);

        try (var factory = mockStatic(HttpClient.class)) {
            factory.when(HttpClient::newHttpClient).thenReturn(client);
            assertSame(failure, assertThrows(IOException.class, () -> ExampleHttp.getBytes("http://fixture.example.test/proof")));
        }

        verify(client).close();
    }
}
