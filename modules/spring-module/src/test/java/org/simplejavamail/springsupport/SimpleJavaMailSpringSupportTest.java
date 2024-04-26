package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { SimpleJavaMailSpringSupport.class })
public class SimpleJavaMailSpringSupportTest {

	@Test
	public void testSpringPropertyPropagation() {

	}
}