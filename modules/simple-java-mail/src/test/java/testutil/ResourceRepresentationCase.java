package testutil;

/** The same resource facts expressed at their filename, HTML attribute and CID URL boundaries. */
public enum ResourceRepresentationCase {
    AMPERSAND("a&b.png", "a&amp;b.png", "logo&tag@example.org", "cid:logo&amp;tag@example.org", true),
    NUMERIC_AMPERSAND("a&b.png", "a&#38;b.png", "logo&tag@example.org", "c&#105;d:logo&#38;tag@example.org", true),
    LITERAL_ENTITY("a&amp;b.png", "a&amp;amp;b.png", "logo&amp;tag@example.org", "cid:logo&amp;amp;tag@example.org", true),
    PERCENT_AND_PLUS("a%2F+b.png", "a%2F+b.png", "logo%2F+1@example.org", "cid:logo%252F+1@example.org", true),
    APOSTROPHE("it's.png", "it&#39;s.png", "logo'1@example.org", "cid:logo&apos;1@example.org", true),
    MISSING_NAME_LABEL("UnknownAttachment", "UnknownAttachment", "missing-name@example.org", "cid:missing-name&commat;example.org", true),
    BRACKETS("<draft>.png", "&lt;draft&gt;.png", "logo[1]@example.org", "cid:logo%5B1%5D@example.org", false),
    DOUBLE_QUOTE("say\"hi.png", "say&quot;hi.png", "logo\"1@example.org", "cid:logo&quot;1@example.org", false);

    private final String filename;
    private final String htmlLocation;
    private final String contentId;
    private final String htmlCidReference;
    private final boolean portableFilename;

    ResourceRepresentationCase(final String filename, final String htmlLocation, final String contentId,
            final String htmlCidReference, final boolean portableFilename) {
        this.filename = filename;
        this.htmlLocation = htmlLocation;
        this.contentId = contentId;
        this.htmlCidReference = htmlCidReference;
        this.portableFilename = portableFilename;
    }

    public String getFilename() { return filename; }
    public String getHtmlLocation() { return htmlLocation; }
    public String getContentId() { return contentId; }
    public String getHtmlCidReference() { return htmlCidReference; }
    public boolean isPortableFilename() { return portableFilename; }

    /** A resource that must not be selected by this case's HTML CID reference. */
    public String getDifferentContentId() {
        switch (this) {
            case AMPERSAND: return "logo&amp;tag@example.org";
            case NUMERIC_AMPERSAND: return "logo&#38;tag@example.org";
            case LITERAL_ENTITY: return "logo&tag@example.org";
            case PERCENT_AND_PLUS: return "logo/+1@example.org";
            case APOSTROPHE: return "logo&apos;1@example.org";
            case MISSING_NAME_LABEL: return "Missing-name@example.org";
            case BRACKETS: return "logo%5B1%5D@example.org";
            case DOUBLE_QUOTE: return "logo&quot;1@example.org";
            default: throw new AssertionError(this);
        }
    }
}
