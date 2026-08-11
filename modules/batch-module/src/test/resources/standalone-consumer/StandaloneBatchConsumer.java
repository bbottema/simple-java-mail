import jakarta.mail.Session;
import org.simplejavamail.batch.BatchTransportExecutor;

public final class StandaloneBatchConsumer {
    public static void register(BatchTransportExecutor<String> executor, Session session) {
        executor.registerSession("outbound", session);
    }

    public static void main(String[] args) {
        BatchTransportExecutor<String> executor = BatchTransportExecutor.<String>builder().build();
        if (executor.isShutdown()) {
            throw new AssertionError("new executor unexpectedly closed");
        }
        executor.close();
        if (!executor.isShutdown()) {
            throw new AssertionError("close did not complete shutdown");
        }
    }
}
