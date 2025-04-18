package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SimpleJavaMailSpringSupport.class) // uses application.properties automatically
public class SimpleJavaMailSpringSupportBootTest extends SimpleJavaMailSpringSupportTest {

	@Test
	public void testBootPropertyPropagation() {
		performConfigAssertions();
	}
}
