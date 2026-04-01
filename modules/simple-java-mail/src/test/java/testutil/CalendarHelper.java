package testutil;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;
import net.fortuna.ical4j.util.CompatibilityHints;
import net.fortuna.ical4j.util.FixedUidGenerator;
import net.fortuna.ical4j.util.MapTimeZoneCache;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Creates dummy Calendar event using the <a href="https://github.com/ical4j/ical4j/wiki/Examples">ical4j</a> library.
 */
public final class CalendarHelper {
    private CalendarHelper() {
    }

    public static String createCalendarEvent() {
        try {
            return createICalInvitation();
        } catch (IOException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static String createICalInvitation() throws IOException {
        System.setProperty("net.fortuna.ical4j.timezone.cache.internal", MapTimeZoneCache.class.getName());
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_OUTLOOK_COMPATIBILITY, true);

        Calendar cal = createCalendar();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        new CalendarOutputter().output(cal, bout);
        return bout.toString("UTF-8");
    }

    @NotNull
    private static net.fortuna.ical4j.model.Calendar createCalendar() throws SocketException {
        ZoneId zoneId = ZoneId.of("America/Mexico_City");
        ZonedDateTime startZonedDateTime = ZonedDateTime.of(2028, 4, 1, 9, 0, 0, 0, zoneId);
        ZonedDateTime endZonedDateTime = ZonedDateTime.of(2028, 4, 1, 13, 0, 0, 0, zoneId);

        return new Calendar()
                .add(new ProdId("-//Events Calendar//iCal4j 1.0//EN"))
                .add(ImmutableVersion.VERSION_2_0)
                .<Calendar>add(ImmutableCalScale.GREGORIAN)
                .add(new VEvent(startZonedDateTime, endZonedDateTime, "Progress Meeting")
                        .add(new FixedUidGenerator("uidGen").generateUid())
                        .add(new Organizer(URI.create("mailto:lead-dev@mycompany.com"))
                                .<Organizer>add(new Cn("Lead developer")))
                        .add(new Attendee(URI.create("mailto:dev1@mycompany.com"))
                                .add(Role.REQ_PARTICIPANT)
                                .add(new Cn("Developer 1")))
                        .<VEvent>add(new Attendee(URI.create("mailto:dev2@mycompany.com"))
                                .add(Role.OPT_PARTICIPANT)
                                .add(new Cn("Developer 2"))))
                .add(TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone("America/Mexico_City").getVTimeZone());
    }
}
