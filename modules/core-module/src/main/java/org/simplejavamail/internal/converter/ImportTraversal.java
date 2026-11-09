package org.simplejavamail.internal.converter;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A scope in one import's counted traversal, shared only by that import and its selected nested views. */
@ApiStatus.Internal
public final class ImportTraversal {
    private static final ImportTraversal UNLIMITED = new ImportTraversal(new Allowance(0, 0), "source", 1);
    private final Allowance allowance;
    private final String path;
    private final int depth;

    private ImportTraversal(final Allowance allowance, final String path, final int depth) {
        this.allowance = allowance;
        this.path = path;
        this.depth = depth;
    }

    public static ImportTraversal start(final int maximumParts, final int maximumDepth) {
        if (maximumParts < 0 || maximumDepth < 0) throw new IllegalArgumentException("Import limits cannot be negative");
        return maximumParts == 0 && maximumDepth == 0 ? UNLIMITED
                : new ImportTraversal(new Allowance(maximumParts, maximumDepth), "source", 1);
    }

    public static ImportTraversal unlimited() { return start(0, 0); }

    /** A body part or embedded-message root is one level below its owning entity. */
    public ImportTraversal child(final String name) {
        return hasLimits() ? new ImportTraversal(allowance, path + "/" + name, depth + 1) : this;
    }

    /** An extracted representation occupies the original entity's position without renewing its allowance. */
    public ImportTraversal view(final String name) {
        return hasLimits() ? new ImportTraversal(allowance, path + "/views[" + name + "]", depth) : this;
    }

    /** Repeated access to an admitted source occurrence does not spend another part. */
    public boolean admit() {
        if (!hasLimits()) return true;
        if (allowance.admitted.contains(path)) return true;
        if (depthExceeded() || capacityExhausted()) {
            allowance.omitted.add(path);
            return false;
        }
        allowance.admitted.add(path);
        return true;
    }

    public boolean isAdmitted() { return !hasLimits() || allowance.admitted.contains(path); }

    public boolean capacityExhausted() {
        return allowance.maximumParts > 0 && allowance.admitted.size() >= allowance.maximumParts;
    }

    public boolean hasLimits() { return allowance.maximumParts > 0 || allowance.maximumDepth > 0; }
    public boolean hasOmissions() { return !allowance.omitted.isEmpty(); }

    /** Native modules record a bounded observation on an admitted owner; main captures its actual source reference later. */
    public void noteStoppedChildren() {
        if (hasLimits()) allowance.stops.putIfAbsent(path, childStopDescription());
    }

    public String stoppedChildrenDescription() { return allowance.stops.get(path); }

    public String stopDescription() {
        if (depthExceeded()) return "Import maximumNestingDepth limit of " + allowance.maximumDepth + " was exceeded";
        return "Import maximumPartCount limit of " + allowance.maximumParts + " was exceeded";
    }

    public String childStopDescription() {
        if (allowance.maximumDepth > 0 && depth >= allowance.maximumDepth) {
            return "Import maximumNestingDepth limit of " + allowance.maximumDepth + " prevented visiting deeper content";
        }
        return "Import maximumPartCount limit of " + allowance.maximumParts + " prevented visiting further content";
    }

    private boolean depthExceeded() { return allowance.maximumDepth > 0 && depth > allowance.maximumDepth; }

    private static final class Allowance {
        private final int maximumParts;
        private final int maximumDepth;
        private final Set<String> admitted = new HashSet<>();
        private final Set<String> omitted = new HashSet<>();
        private final Map<String, String> stops = new HashMap<>();

        private Allowance(final int maximumParts, final int maximumDepth) {
            this.maximumParts = maximumParts;
            this.maximumDepth = maximumDepth;
        }
    }
}
