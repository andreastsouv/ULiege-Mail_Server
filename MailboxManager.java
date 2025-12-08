import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MailboxManager {

    private final File baseDir;

    public MailboxManager() {
        this.baseDir = new File("storage");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    public synchronized void storeLocalMessage(String recipientEmail, String rawMessage) throws IOException {
        File userDir = getOrCreateUserDir(recipientEmail);
        // TODO: later use real UID-based filenames and metadata
        File msgFile = File.createTempFile("msg_", ".txt", userDir);
        try (FileWriter fw = new FileWriter(msgFile)) {
            fw.write(rawMessage);
        }
    }

    public synchronized List<File> listMessages(String userEmail) {
        File userDir = getOrCreateUserDir(userEmail);
        File[] files = userDir.listFiles(File::isFile);
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, Comparator
                .comparingLong(File::lastModified)
                .thenComparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }

    public synchronized void deleteMessage(File messageFile) throws IOException {
        if (messageFile == null) {
            throw new IllegalArgumentException("messageFile cannot be null");
        }
        if (!messageFile.exists() || !messageFile.isFile()) {
            throw new IOException("Message file does not exist: " + messageFile.getName());
        }
        if (!messageFile.delete()) {
            throw new IOException("Failed to delete message: " + messageFile.getName());
        }
    }

    public synchronized List<String> readMessageLines(File messageFile) throws IOException {
        File validated = validateMessageFile(messageFile);
        return Files.readAllLines(validated.toPath());
    }

    public synchronized long messageSize(File messageFile) throws IOException {
        File validated = validateMessageFile(messageFile);
        return validated.length();
    }

    private File getOrCreateUserDir(String userEmail) {
        File userDir = new File(baseDir, userEmail);
        if (!userDir.exists()) {
            userDir.mkdirs();
        }
        return userDir;
    }

    private File validateMessageFile(File messageFile) throws IOException {
        if (messageFile == null) {
            throw new IllegalArgumentException("messageFile cannot be null");
        }
        if (!messageFile.exists() || !messageFile.isFile()) {
            throw new IOException("Message file does not exist: " + messageFile.getName());
        }
        return messageFile;
    }
}
