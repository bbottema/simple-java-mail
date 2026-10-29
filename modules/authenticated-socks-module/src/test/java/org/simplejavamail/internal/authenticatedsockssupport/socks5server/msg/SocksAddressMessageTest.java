package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.Socks5;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class SocksAddressMessageTest {

    @Test
    void readsTheCompleteIpv6DestinationAndLeavesFollowingDataUntouched() throws Exception {
        final byte[] address = InetAddress.getByName("2001:db8::25").getAddress();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {5, 1, 0, 4});
        bytes.write(address);
        bytes.write(new byte[] {2, 75, 42});
        final ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        final CommandMessage command = new CommandMessage();

        command.read(input);

        assertThat(command.hasSocksException()).isFalse();
        assertThat(command.getInetAddress().getAddress()).containsExactly(address);
        assertThat(command.getPort()).isEqualTo(587);
        assertThat(input.read()).isEqualTo(42);
    }

    @Test
    void doesNotResolveADomainDestinationInsideTheBridge() throws Exception {
        final byte[] host = "smtp.proxy-only.invalid".getBytes(UTF_8);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {5, 1, 0, 3, (byte) host.length});
        bytes.write(host);
        bytes.write(new byte[] {2, 75, 42});
        final ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        final CommandMessage command = new CommandMessage();

        command.read(input);

        assertThat(command.hasSocksException()).isFalse();
        assertThat(command.getPort()).isEqualTo(587);
        assertThat(command.getSocketAddress().isUnresolved()).isTrue();
        assertThat(command.getSocketAddress().getHostString()).isEqualTo("smtp.proxy-only.invalid");
        assertThat(input.read()).isEqualTo(42);
    }

    @Test
    void preservesAnExplicitIpv6AddressEvenWhenItContainsAnIpv4MappedValue() throws Exception {
        final byte[] address = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 192, 0, 2, 25};
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[] {5, 1, 0, 4});
        request.write(address);
        request.write(new byte[] {2, 75});
        final CommandMessage command = new CommandMessage();
        command.read(new ByteArrayInputStream(request.toByteArray()));
        final ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        final Socket proxy = new Socket() {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            }
            @Override public OutputStream getOutputStream() { return forwarded; }
        };

        final Socks5 client = new Socks5(new InetSocketAddress("127.0.0.1", 1080));
        client.setProxySocket(proxy);
        client.requestConnect(command.getSocketAddress());

        assertThat(forwarded.toByteArray()).containsExactly(request.toByteArray());
        assertThat(command.getSocketAddress().getAddress().getAddress()).containsExactly(address);
    }

    @Test
    void writesAllSixteenBoundAddressBytesAndTheIpv6AddressType() throws Exception {
        final byte[] address = InetAddress.getByName("2001:db8::1234").getAddress();
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(new byte[] {5, 0, 0, 4});
        expected.write(address);
        expected.write(new byte[] {2, 75});

        assertThat(CommandResponseMessage.getBytes(ServerReply.SUCCEEDED, InetAddress.getByAddress(address), 587))
                .containsExactly(expected.toByteArray());
    }

    @Test
    void retainsIpv4BoundAddressFraming() throws Exception {
        assertThat(CommandResponseMessage.getBytes(ServerReply.SUCCEEDED,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 587))
                .containsExactly(5, 0, 0, 1, 127, 0, 0, 1, 2, 75);
    }
}
