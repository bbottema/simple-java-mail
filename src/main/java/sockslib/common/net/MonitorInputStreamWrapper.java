package sockslib.common.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static sockslib.utils.Utils.Assert.checkNotNull;

class MonitorInputStreamWrapper extends InputStream {

  private final InputStream originalInputStream;
  private final NetworkMonitor networkMonitor;

  private MonitorInputStreamWrapper(InputStream inputStream, NetworkMonitor networkMonitor) {
    originalInputStream = checkNotNull(inputStream);
    this.networkMonitor = networkMonitor;
  }

  public static InputStream wrap(InputStream inputStream, NetworkMonitor networkMonitor) {
    return new MonitorInputStreamWrapper(inputStream, networkMonitor);
  }

  @Override
  public int read() throws IOException {
    int b = originalInputStream.read();
    byte[] array = {(byte) b};
    informMonitor(array);
    return b;
  }

  @Override
  public void close() throws IOException {
    originalInputStream.close();
  }

  @Override
  public int available() throws IOException {
    return originalInputStream.available();
  }

  @Override
  public long skip(long n) throws IOException {
    return originalInputStream.skip(n);
  }

  @Override
  public synchronized void reset() throws IOException {
    originalInputStream.reset();
  }

  @Override
  public synchronized void mark(int readLimit) {
    originalInputStream.mark(readLimit);
  }

  @Override
  public boolean markSupported() {
    return originalInputStream.markSupported();
  }

  @Override
  public int read(byte[] b) throws IOException {
    int length = originalInputStream.read(b);
    if (length > 0) {
      informMonitor(b, 0, length);
    }
    return length;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int length = originalInputStream.read(b, off, len);
    if (length > 0) {
      informMonitor(b, off, length);
    }
    return length;
  }

  private void informMonitor(byte[] bytes) {
    networkMonitor.onRead(bytes);
  }

  private void informMonitor(byte[] bytes, int off, int len) {
    networkMonitor.onRead(Arrays.copyOfRange(bytes, off, off + len));
  }
}
