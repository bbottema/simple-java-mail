package demo;

import lombok.SneakyThrows;
import lombok.val;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.MiscUtil;
import testutil.ModuleLoaderTestHelper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static java.lang.String.format;
import static org.bbottema.genericobjectpool.util.SleepUtil.sleep;

public class DependencyChecker extends DemoAppBase {

    static {
        // make Simple Java Mail ignore the batch module, so the JVM is never blocked from shutting down (because of the connection pool)
        ModuleLoaderTestHelper._forceDisableBatchModule();
    }

    public static void main(String[] args) throws IOException {
        val group = MiscUtil.checkArgumentNotEmpty(args[0], "arg 0 should be the group id");
        val artifact = MiscUtil.checkArgumentNotEmpty(args[1], "arg 1 should be the artifact id");
        val version = MiscUtil.checkArgumentNotEmpty(args[2], "arg 2 should be the version");

        val urlString = format("https://repo1.maven.org/maven2/%s/%s/%s", group.replaceAll("\\W", "/"), artifact, version);
        HttpURLConnection connection = null;

        System.out.println("Checking URL: " + urlString);

        while (true) {
            try {
                connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setRequestMethod("HEAD");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    String dependency = group + ":" + artifact + ":" + version;
                    mailerTLSBuilder
                            .withDebugLogging(false)
                            .buildMailer()
                            .sendMail(EmailBuilder.startingBlank()
                                    .from("Dependency Checker", "no-reply@dependency-checker.com")
                                    .to(YOUR_GMAIL_ADDRESS)
                                    .withSubject("Dependency available: " + dependency)
                                    .withPlainText(format("The dependency '%s' is available at %s", dependency, urlString))
                                    .buildEmail());

                    // Windows 10 notification (lower right corner) - requires Windows 10 and the Windows 10 JDK:
                    val notificationTitle = showOSNotification("Notification Title", format("The dependency '%s' is available", dependency));
                    sleep(10_000);
                    SystemTray.getSystemTray().remove(notificationTitle);
                    break;
                }
            } catch (IOException e) {
                System.err.println("Error while checking the URL: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            sleep(1000);
        }
    }

    @SneakyThrows
    public static TrayIcon showOSNotification(String notificationTitle, String msg) {
        TrayIcon trayIcon = new TrayIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "Notification Helper");
        SystemTray.getSystemTray().add(trayIcon);
        trayIcon.displayMessage(notificationTitle, msg, TrayIcon.MessageType.INFO);
        return trayIcon;
    }
}
