/*
 * Copyright 2015-2025 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sockslib.common;

import static sockslib.utils.Util.checkNotNull;
/**
 * The class <code>KeyStoreInfo</code> represents a key store information.
 *
 * @author Youchao Feng
 * @version 1.0
 * @since 1.0
 */
public class KeyStoreInfo {

  private final String keyStorePath;
  private final String password;
  private String type = "JKS";

  @SuppressWarnings("SameParameterValue")
  public KeyStoreInfo(String keyStorePath, String password, String type) {
    this.keyStorePath = checkNotNull(keyStorePath, "Argument [keyStorePath] may not be null");
    this.password = checkNotNull(password, "Argument [password] may not be null");
    this.type = checkNotNull(type, "Argument [type] may not be null");
  }

  public KeyStoreInfo(String keyStorePath, String password) {
    this(keyStorePath, password, "JKS");
  }

  public String getKeyStorePath() {
    return keyStorePath;
  }

  public String getPassword() {
    return password;
  }

  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return "[KEY STORE] PATH:" + keyStorePath + " PASSWORD:" + password + " TYPE:" + type;
  }

}
