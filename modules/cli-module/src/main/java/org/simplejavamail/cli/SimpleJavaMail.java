/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.cli;

import static org.simplejavamail.internal.clisupport.CliSupport.*;

/**
 * Entry class when using the command line interface. Wires all the args into the CLI support.
 * <p>
 * For CLI usage help, simply add the {@code --help} flag or add it to any available option, like so: <br>
 * <strong>{@code sjm send --mailer:async--help}</strong>.
 *
 * @see <a href="http://www.simplejavamail.org/modules.html#cli-module">More about the CLI module</a>
 * @see <a href="http://www.simplejavamail.org/cli.html#navigation">How to use simple Java Mail CLI</a>
 */
public class SimpleJavaMail {
	public static void main(String[] args) {
		runCLI(args);
	}
}