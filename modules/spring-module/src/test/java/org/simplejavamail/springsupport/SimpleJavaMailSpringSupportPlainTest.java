package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SimpleJavaMailSpringSupport.class)
@TestPropertySource(locations = "classpath:application.properties") // unlike Spring Boot, this is a manual step
public class SimpleJavaMailSpringSupportPlainTest extends SimpleJavaMailSpringSupportTest {

	@Test
	public void testPlainSpringPropertyPropagation() {
		performConfigAssertions();
	}
}
