package org.simplejavamail.internal.clisupport;

import org.junit.Test;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;
import org.simplejavamail.internal.clisupport.model.CliCommandData;
import org.simplejavamail.internal.clisupport.model.CliParamData;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

public class CliCommandDataTest {
	@Test
	public void testComparator() {
		List<CliCommandData> unsortedList = new ArrayList<>();
		CliCommandData aa = createDummyCommand(unsortedList, "prefixA", "nameA");
		CliCommandData ab = createDummyCommand(unsortedList, "prefixA", "nameB");
		CliCommandData ac = createDummyCommand(unsortedList, "prefixA", "nameC");
		CliCommandData ba = createDummyCommand(unsortedList, "prefixB", "nameA");
		CliCommandData bb = createDummyCommand(unsortedList, "prefixB", "nameB");
		CliCommandData bc = createDummyCommand(unsortedList, "prefixB", "nameC");
		CliCommandData ca = createDummyCommand(unsortedList, "prefixC", "nameA");
		CliCommandData cb = createDummyCommand(unsortedList, "prefixC", "nameB");
		CliCommandData cc = createDummyCommand(unsortedList, "prefixC", "nameC");
		
		for (int i = 0; i < 10; i++) {
			Collections.shuffle(unsortedList);
			TreeSet<CliCommandData> sortedSet = new TreeSet<>(unsortedList);
			assertThat(sortedSet).containsExactly(aa, ab, ac, ba, bb, bc, ca, cb, cc);
		}
	}
	
	@Nonnull
	private CliCommandData createDummyCommand(List<CliCommandData> unsortedSet, String prefix, String name) {
		CliCommandData instance = new CliCommandData(prefix + ":" + name, new ArrayList<String>(), new ArrayList<CliParamData>(), new ArrayList<CliSupported.RootCommand>(), new ArrayList<CliCommandData>());
		unsortedSet.add(instance);
		return instance;
	}
}