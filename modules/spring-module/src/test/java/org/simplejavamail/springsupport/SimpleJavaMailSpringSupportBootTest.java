package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SimpleJavaMailSpringSupportBootTest.TestApplication.class) // uses application.properties automatically
public class SimpleJavaMailSpringSupportBootTest extends SimpleJavaMailSpringSupportTest {

	@Test
	public void testBootPropertyPropagation() {
		performConfigAssertions();
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class TestApplication {
	}
}
