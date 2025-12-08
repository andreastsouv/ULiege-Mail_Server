import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class POP3Handler implements Runnable {

    private final Socket client;
    private final MailboxManager mailboxManager;
    private final UserManager userManager;
    private final String serverDomain;

    private String currentUser;
    private boolean authenticated = false;
    private List<File> messages = new ArrayList<>();
    private boolean[] deletedFlags = new boolean[0];

    public POP3Handler(Socket client,
                       MailboxManager mailboxManager,
                       UserManager userManager,
                       String serverDomain) {
        this.client = client;
        this.mailboxManager = mailboxManager;
        this.userManager = userManager;
        this.serverDomain = serverDomain;
    }

    @Override
    public void run() {
        try {
            handleSession();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleSession() throws IOException {
        OutputStream out = client.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));

        writeLine(out, "+OK POP3 server ready (" + serverDomain + ")");

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ", 2);
            String cmd = parts[0].toUpperCase();
            String arg = parts.length > 1 ? parts[1].trim() : null;

            switch (cmd) {
                case "USER":
                    handleUser(arg, out);
                    break;
                case "PASS":
                    handlePass(arg, out);
                    break;
                case "STAT":
                    handleStat(out);
                    break;
                case "LIST":
                    handleList(arg, out);
                    break;
                case "RETR":
                    handleRetr(arg, out);
                    break;
                case "DELE":
                    handleDele(arg, out);
                    break;
                case "RSET":
                    handleRset(out);
                    break;
                case "QUIT":
                    handleQuit(out);
                    return;
                default:
                    writeLine(out, "-ERR Unknown command");
            }
        }
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\r\n").getBytes());
        out.flush();
    }

    private void handleUser(String arg, OutputStream out) throws IOException {
        if (arg == null || arg.isEmpty()) {
            writeLine(out, "-ERR Missing username");
            return;
        }

        if (!userManager.isValidUser(arg, serverDomain)) {
            writeLine(out, "-ERR Invalid user");
            return;
        }

        this.currentUser = arg;
        this.authenticated = false;
        writeLine(out, "+OK User accepted");
    }

    private void handlePass(String arg, OutputStream out) throws IOException {
        if (currentUser == null) {
            writeLine(out, "-ERR USER required before PASS");
            return;
        }

        if (arg == null) {
            writeLine(out, "-ERR Missing password");
            return;
        }

        if (userManager.checkPassword(currentUser, arg)) {
            authenticated = true;
            loadMessages();
            writeLine(out, "+OK Authenticated");
        } else {
            writeLine(out, "-ERR Authentication failed");
        }
    }

    private void handleStat(OutputStream out) throws IOException {
        if (!ensureAuthenticated(out)) return;

        long totalSize = 0;
        int count = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletedFlags[i]) {
                count++;
                totalSize += messages.get(i).length();
            }
        }
        writeLine(out, "+OK " + count + " " + totalSize);
    }

    private void handleList(String arg, OutputStream out) throws IOException {
        if (!ensureAuthenticated(out)) return;

        if (arg == null || arg.isEmpty()) {
            writeLine(out, "+OK scan listing follows");
            for (int i = 0; i < messages.size(); i++) {
                if (!deletedFlags[i]) {
                    long size = messages.get(i).length();
                    writeLine(out, (i + 1) + " " + size);
                }
            }
            writeLine(out, ".");
        } else {
            int index = parseIndex(arg);
            if (index == -1) {
                writeLine(out, "-ERR Invalid message number");
                return;
            }
            if (!isExistingAndNotDeleted(index)) {
                writeLine(out, "-ERR No such message");
                return;
            }
            long size = mailboxManager.messageSize(messages.get(index - 1));
            writeLine(out, "+OK " + index + " " + size);
        }
    }

    private void handleRetr(String arg, OutputStream out) throws IOException {
        if (!ensureAuthenticated(out)) return;

        int index = parseIndex(arg);
        if (index == -1) {
            writeLine(out, "-ERR Invalid message number");
            return;
        }
        if (!isExistingAndNotDeleted(index)) {
            writeLine(out, "-ERR No such message");
            return;
        }

        File msgFile = messages.get(index - 1);
        long size = mailboxManager.messageSize(msgFile);
        writeLine(out, "+OK " + size + " octets");

        for (String msgLine : mailboxManager.readMessageLines(msgFile)) {
            writeLine(out, msgLine);
        }
        writeLine(out, ".");
    }

    private void handleDele(String arg, OutputStream out) throws IOException {
        if (!ensureAuthenticated(out)) return;

        int index = parseIndex(arg);
        if (index == -1) {
            writeLine(out, "-ERR Invalid message number");
            return;
        }
        if (!isExistingAndNotDeleted(index)) {
            writeLine(out, "-ERR No such message");
            return;
        }

        deletedFlags[index - 1] = true;
        writeLine(out, "+OK Message " + index + " marked for deletion");
    }

    private void handleRset(OutputStream out) throws IOException {
        if (!ensureAuthenticated(out)) return;

        Arrays.fill(deletedFlags, false);
        writeLine(out, "+OK Deletion marks cleared");
    }

    private void handleQuit(OutputStream out) throws IOException {
        if (authenticated) {
            for (int i = 0; i < messages.size(); i++) {
                if (deletedFlags[i]) {
                    mailboxManager.deleteMessage(messages.get(i));
                }
            }
        }
        writeLine(out, "+OK Goodbye");
    }

    private boolean ensureAuthenticated(OutputStream out) throws IOException {
        if (!authenticated) {
            writeLine(out, "-ERR Not authenticated");
            return false;
        }
        return true;
    }

    private int parseIndex(String arg) {
        if (arg == null || arg.isEmpty()) {
            return -1;
        }
        try {
            int idx = Integer.parseInt(arg);
            return idx > 0 ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isExistingAndNotDeleted(int index) {
        return index >= 1 && index <= messages.size() && !deletedFlags[index - 1];
    }

    private void loadMessages() {
        messages = mailboxManager.listMessages(currentUser);
        deletedFlags = new boolean[messages.size()];
    }
}
