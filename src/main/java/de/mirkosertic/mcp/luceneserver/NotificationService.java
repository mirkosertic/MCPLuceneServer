package de.mirkosertic.mcp.luceneserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final String os;

    public NotificationService() {
        this.os = System.getProperty("os.name").toLowerCase();
        logger.info("NotificationService initialized for OS: {}", os);
    }

    public void notify(final String title, final String message) {
        try {
            if (os.contains("mac")) {
                notifyMacOS(title, message);
            } else if (os.contains("win")) {
                notifyWindows(title, message);
            } else if (os.contains("linux")) {
                notifyLinux(title, message);
            } else {
                logger.debug("Notifications not supported on this OS: {}", os);
            }
        } catch (final IOException | InterruptedException e) {
            logger.debug("Failed to send notification: {}", e.getMessage());
        }
    }

    private void notifyMacOS(final String title, final String message) throws IOException {
        // Use display notification to show in macOS Notification Center (top-right corner)
        final ProcessBuilder pb = new ProcessBuilder(
                "osascript", "-e",
                String.format("display notification \"%s\" with title \"%s\"",
                        escapeForAppleScript(message),
                        escapeForAppleScript(title))
        );
        pb.start();
        // Don't wait - notifications are inherently asynchronous and non-blocking
    }

    private void notifyWindows(final String title, final String message) throws IOException, InterruptedException {
        // Use PowerShell to show a toast notification
        final String script = String.format(
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; " +
                "$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02); " +
                "$textNodes = $template.GetElementsByTagName('text'); " +
                "$textNodes.Item(0).AppendChild($template.CreateTextNode('%s')) | Out-Null; " +
                "$textNodes.Item(1).AppendChild($template.CreateTextNode('%s')) | Out-Null; " +
                "$toast = [Windows.UI.Notifications.ToastNotification]::new($template); " +
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('MCP Lucene Server').Show($toast);",
                escapeForPowerShell(title),
                escapeForPowerShell(message)
        );
        final ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", script);
        pb.inheritIO();
        final Process p = pb.start();
        p.waitFor();
    }

    private void notifyLinux(final String title, final String message) throws IOException, InterruptedException {
        // Use notify-send (available on most Linux desktops)
        final ProcessBuilder pb = new ProcessBuilder("notify-send", title, message);
        pb.inheritIO();
        final Process p = pb.start();
        p.waitFor();
    }

    private String escapeForAppleScript(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeForPowerShell(final String text) {
        return text.replace("'", "''").replace("\"", "`\"");
    }
}
