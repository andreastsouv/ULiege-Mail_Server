import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MailboxManager {

    private final File baseDir;

    public MailboxManager() {
        this.baseDir = new File("storage");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    public synchronized void storeLocalMessage(String recipientEmail, String rawMessage) throws IOException {
        File userDir = new File(baseDir, recipientEmail);
        if (!userDir.exists()) {
            userDir.mkdirs();
        }
        // TODO: later use real UID-based filenames and metadata
        File msgFile = File.createTempFile("msg_", ".txt", userDir);
        try (FileWriter fw = new FileWriter(msgFile)) {
            fw.write(rawMessage);
        }
    }
}
