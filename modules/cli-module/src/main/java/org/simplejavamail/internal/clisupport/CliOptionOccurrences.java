package org.simplejavamail.internal.clisupport;

import picocli.CommandLine.Model.IGetter;
import picocli.CommandLine.Model.ISetter;

import java.util.ArrayList;
import java.util.List;

/** Captures Picocli's completed builder-option assignments in their actual command-line order. */
final class CliOptionOccurrences implements IGetter {

    private final List<Occurrence> occurrences = new ArrayList<>();

    /** Each parser assignment collects arguments for one invocation, without earlier invocations' values. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get() {
        // A non-null empty collection also avoids Picocli's separate collection-initialization setter call.
        return (T) new ArrayList<String>();
    }

    ISetter setterFor(final String optionName) {
        return new ISetter() {
            @Override
            public <T> T set(final T value) {
                if (value == null) {
                    // Picocli resets every option to its null initial value before it starts parsing a command.
                    occurrences.clear();
                } else {
                    @SuppressWarnings("unchecked") final List<String> arguments = (List<String>) value;
                    occurrences.add(new Occurrence(optionName, new ArrayList<>(arguments)));
                }
                return null;
            }
        };
    }

    List<Occurrence> occurrences() {
        return occurrences;
    }

    record Occurrence(String optionName, List<String> arguments) {
    }
}
