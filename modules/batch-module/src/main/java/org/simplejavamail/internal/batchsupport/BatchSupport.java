package org.simplejavamail.internal.batchsupport;

import org.simplejavamail.internal.modules.BatchModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class BatchSupport implements BatchModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchSupport.class);
	
}