package org.simplejavamail.examples.patterns;

/** Connects each example's framework to its shared application code. These are not Simple Java Mail extension points. */
public final class PatternPorts {
    private PatternPorts() { }

    public interface WorkTrigger {
        void submit(String id);
    }

    public interface FeedbackSink {
        void accept(String id, String action);
    }
}
