package fixtures.received;

import org.simplejavamail.api.outlook.OutlookMessageData;

/** Continuing published getters: neither value implies the final selected HTML or original MSG bytes. */
public final class NativeBodies {
    public String nativeHtml(OutlookMessageData source) {
        return source.getBodyHTML();
    }

    public String readerDecodedRtf(OutlookMessageData source) {
        return source.getBodyRTF();
    }
}
