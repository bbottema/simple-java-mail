package org.simplejavamail.internal.clisupport;

import org.junit.Test;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

public class CliDeclaredOptionSpecTest {
	@Test
	public void testComparator() {
		List<CliDeclaredOptionSpec> unsortedList = new ArrayList<>();
		CliDeclaredOptionSpec aa = createDummyCommand(unsortedList, "prefixA", "nameA");
		CliDeclaredOptionSpec ab = createDummyCommand(unsortedList, "prefixA", "nameB");
		CliDeclaredOptionSpec ac = createDummyCommand(unsortedList, "prefixA", "nameC");
		CliDeclaredOptionSpec ba = createDummyCommand(unsortedList, "prefixB", "nameA");
		CliDeclaredOptionSpec bb = createDummyCommand(unsortedList, "prefixB", "nameB");
		CliDeclaredOptionSpec bc = createDummyCommand(unsortedList, "prefixB", "nameC");
		CliDeclaredOptionSpec ca = createDummyCommand(unsortedList, "prefixC", "nameA");
		CliDeclaredOptionSpec cb = createDummyCommand(unsortedList, "prefixC", "nameB");
		CliDeclaredOptionSpec cc = createDummyCommand(unsortedList, "prefixC", "nameC");
		
		for (int i = 0; i < 10; i++) {
			Collections.shuffle(unsortedList);
			TreeSet<CliDeclaredOptionSpec> sortedSet = new TreeSet<>(unsortedList);
			assertThat(sortedSet).containsExactly(aa, ab, ac, ba, bb, bc, ca, cb, cc);
		}
	}
	
	@Nonnull
	private CliDeclaredOptionSpec createDummyCommand(List<CliDeclaredOptionSpec> unsortedSet, String prefix, String name) {
		@SuppressWarnings("ConstantConditions")
		CliDeclaredOptionSpec instance = new CliDeclaredOptionSpec(prefix + ":" + name, new ArrayList<String>(), new ArrayList<CliDeclaredOptionValue>(), new ArrayList<CliCommandType>(), null);
		unsortedSet.add(instance);
		return instance;
	}
}