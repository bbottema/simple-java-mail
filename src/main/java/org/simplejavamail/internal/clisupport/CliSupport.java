package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static java.util.Arrays.asList;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_API = { EmailBuilder.EmailBuilderInstance.class, EmailBuilder.class };
	
	public static void runCLI(String[] args) {
		Collection<CliParam> parameterMap = generateParameterNetwork();
	}
	
	private static Collection<CliParam> generateParameterNetwork() {
		ArrayList<CliParam> cliParams = new ArrayList<>();
		for (Class<?> apiRoot : RELEVANT_BUILDER_API) {
			cliParams.add(generateParameterNetwork(apiRoot));
		}
		return cliParams;
	}
	
	private static CliParam generateParameterNetwork(Class<?> apiRoot) {
		CliParam cliParam = new CliParam(apiRoot.getAnnotation(CliSupported.class).value(), Collections.<Class<?>>emptyList());
		
		for (Method m : apiRoot.getMethods()) {
			if (m.isAnnotationPresent(CliSupported.class)) {
				cliParam.getValidNextParams().add(new CliParam(
						m.getAnnotation(CliSupported.class).value(),
						asList(m.getParameterTypes())
				));
			}
		}
		
		return cliParam;
	}
}
