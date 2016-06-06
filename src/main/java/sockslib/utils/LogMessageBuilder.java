/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sockslib.utils;

/**
 * <code>LogMessageBuilder</code> a tool class to generate some debug message.
 *
 * @author Youchao Feng
 * @version 1.0
 */
public class LogMessageBuilder {

  /**
   * Returns a log message.
   *
   * @param bytes Bytes array.
   * @param type  Message type.
   * @return Log message.
   */
  public static String build(byte[] bytes, MsgType type) {
    return build(bytes, bytes.length, type);
  }

  /**
   * Returns a log message.
   *
   * @param bytes Bytes array.
   * @param size  data length in bytes array.
   * @param type  Message type.
   * @return Log message.
   */
  public static String build(byte[] bytes, final int size, MsgType type) {
    StringBuilder debugMsg = new StringBuilder();
    switch (type) {
      case RECEIVE:
        debugMsg.append("Received: ");
        break;
      case SEND:
        debugMsg.append("Sent: ");
        break;
      default:
        break;

    }

    for (int i = 0; i < size; i++) {
      int x = UnsignedByte.toInt(bytes[i]);
      debugMsg.append(Integer.toHexString(x)).append(" ");
    }
    return debugMsg.toString();
  }

  public enum MsgType {
    SEND, RECEIVE
  }

}
