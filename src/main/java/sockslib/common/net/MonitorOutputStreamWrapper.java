package sockslib.common.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import static sockslib.utils.Utils.Assert.checkNotNull;

class MonitorOutputStreamWrapper extends OutputStream {

  private final OutputStream originalOutputStream;

  private final NetworkMonitor networkMonitor;

  private MonitorOutputStreamWrapper(OutputStream outputStream, NetworkMonitor networkMonitor) {
    originalOutputStream = checkNotNull(outputStream);
    this.networkMonitor = networkMonitor;
  }

  public static OutputStream wrap(OutputStream outputStream, NetworkMonitor networkMonitor) {
    return new MonitorOutputStreamWrapper(outputStream, networkMonitor);
  }

  @Override
  public void write(int b) throws IOException {
    originalOutputStream.write(b);
    byte[] bytes = {(byte) b};
    informMonitor(bytes);
  }

  @Override
  public void close() throws IOException {
    originalOutputStream.close();
  }

  @Override
  public void flush() throws IOException {
    originalOutputStream.flush();
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    originalOutputStream.write(b, off, len);
    informMonitor(b, off, len);
  }

  @Override
  public void write(byte[] b) throws IOException {
    originalOutputStream.write(b);
    informMonitor(b);
  }

  private void informMonitor(byte[] bytes) {
    networkMonitor.onWrite(bytes);
  }

  private void informMonitor(byte[] bytes, int off, int length) {
    networkMonitor.onWrite(Arrays.copyOfRange(bytes, off, off + length));
  }
}
