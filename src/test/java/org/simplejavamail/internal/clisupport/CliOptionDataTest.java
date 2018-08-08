package org.simplejavamail.internal.clisupport;

import org.junit.Test;
import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.internal.clisupport.model.CliOptionValueData;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

public class CliOptionDataTest {
	@Test
	public void testComparator() {
		List<CliOptionData> unsortedList = new ArrayList<>();
		CliOptionData aa = createDummyCommand(unsortedList, "prefixA", "nameA");
		CliOptionData ab = createDummyCommand(unsortedList, "prefixA", "nameB");
		CliOptionData ac = createDummyCommand(unsortedList, "prefixA", "nameC");
		CliOptionData ba = createDummyCommand(unsortedList, "prefixB", "nameA");
		CliOptionData bb = createDummyCommand(unsortedList, "prefixB", "nameB");
		CliOptionData bc = createDummyCommand(unsortedList, "prefixB", "nameC");
		CliOptionData ca = createDummyCommand(unsortedList, "prefixC", "nameA");
		CliOptionData cb = createDummyCommand(unsortedList, "prefixC", "nameB");
		CliOptionData cc = createDummyCommand(unsortedList, "prefixC", "nameC");
		
		for (int i = 0; i < 10; i++) {
			Collections.shuffle(unsortedList);
			TreeSet<CliOptionData> sortedSet = new TreeSet<>(unsortedList);
			assertThat(sortedSet).containsExactly(aa, ab, ac, ba, bb, bc, ca, cb, cc);
		}
	}
	
	@Nonnull
	private CliOptionData createDummyCommand(List<CliOptionData> unsortedSet, String prefix, String name) {
		CliOptionData instance = new CliOptionData(prefix + ":" + name, new ArrayList<String>(), new ArrayList<CliOptionValueData>(), new ArrayList<CliCommand>());
		unsortedSet.add(instance);
		return instance;
	}
}