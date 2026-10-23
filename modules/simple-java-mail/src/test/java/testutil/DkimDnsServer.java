package testutil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/** A task-local DNS peer serving one TXT value; no Internet lookup or global JNDI installation. */
public final class DkimDnsServer implements AutoCloseable {
    private final DatagramSocket socket;
    private final Thread worker;
    private final String record;
    private final ConcurrentLinkedQueue<String> questions = new ConcurrentLinkedQueue<String>();
    private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

    public DkimDnsServer(String record) throws Exception {
        this.record = record;
        socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    DatagramPacket request = new DatagramPacket(new byte[4096], 4096);
                    socket.receive(request);
                    byte[] query = request.getData();
                    int position = 12;
                    StringBuilder name = new StringBuilder();
                    while ((query[position] & 0xff) != 0) {
                        int length = query[position++] & 0xff;
                        if (name.length() > 0) { name.append('.'); }
                        name.append(new String(query, position, length, StandardCharsets.US_ASCII));
                        position += length;
                    }
                    questions.add(name.toString());
                    int questionEnd = position + 5;
                    ByteArrayOutputStream result = new ByteArrayOutputStream();
                    DataOutputStream response = new DataOutputStream(result);
                    response.write(query, 0, 2);
                    response.writeShort(0x8180);
                    response.writeShort(1);
                    response.writeShort(1);
                    response.writeShort(0);
                    response.writeShort(0);
                    response.write(query, 12, questionEnd - 12);
                    response.writeShort(0xc00c);
                    response.writeShort(16);
                    response.writeShort(1);
                    response.writeInt(60);
                    byte[] value = DkimDnsServer.this.record.getBytes(StandardCharsets.US_ASCII);
                    response.writeShort(value.length + (value.length + 254) / 255);
                    for (int offset = 0; offset < value.length; offset += 255) {
                        int count = Math.min(255, value.length - offset);
                        response.writeByte(count);
                        response.write(value, offset, count);
                    }
                    byte[] bytes = result.toByteArray();
                    socket.send(new DatagramPacket(bytes, bytes.length, request.getSocketAddress()));
                }
            } catch (SocketException e) {
                if (!socket.isClosed()) { failure.set(e); }
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "dkim-test-dns");
        worker.setDaemon(true);
        worker.start();
    }

    public String url() { return "dns://127.0.0.1:" + socket.getLocalPort(); }
    public boolean received(String recordName) { return questions.contains(recordName); }
    public int requestCount() { return questions.size(); }

    public void close() throws Exception {
        socket.close();
        worker.join(5000);
        if (worker.isAlive()) { throw new AssertionError("DNS peer did not stop"); }
        if (failure.get() != null) { throw new AssertionError("DNS peer failed", failure.get()); }
    }
}
