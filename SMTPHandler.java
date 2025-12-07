import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class SMTPHandler implements Runnable {

    private final Socket client;
    private final MailboxManager mailboxManager;
    private final UserManager userManager;
    private final String serverDomain;

    public SMTPHandler(Socket client,
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
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream()));
        OutputStream out = client.getOutputStream();

        // Greeting (220)
        writeLine(out, "220 " + serverDomain + " Service ready");

        String line;
        boolean quit = false;
        String mailFrom = null;
        List<String> recipients = new ArrayList<>();
        boolean inData = false;
        StringBuilder dataBuffer = new StringBuilder();

        while (!quit && (line = in.readLine()) != null) {
            if (inData) {
                if (line.equals(".")) {
                    String rawMessage = dataBuffer.toString();
                    try {
                        for (String recipient : recipients) {
                            mailboxManager.storeLocalMessage(recipient, rawMessage);
                        }
                        writeLine(out, "250 OK");
                    } catch (IOException e) {
                        writeLine(out, "451 Requested action aborted: local error in processing");
                    }

                    // Reset state for the next transaction regardless of storage result
                    mailFrom = null;
                    recipients.clear();
                    dataBuffer = new StringBuilder();
                    inData = false;
                } else {
                    dataBuffer.append(line).append("\r\n");
                }
                continue;
            }

            String command = line.trim();

            if (command.toUpperCase().startsWith("HELO")) {
                writeLine(out, "250 " + serverDomain + " greets " + extractArg(command));
                // Reset any previous transaction state
                mailFrom = null;
                recipients.clear();
            } else if (command.equalsIgnoreCase("MAIL FROM:")) {
                writeLine(out, "501 Syntax error in parameters or arguments");
            } else if (command.toUpperCase().startsWith("MAIL FROM:")) {
                String email = extractEmailArg(command, "MAIL FROM:");
                if (email == null) {
                    writeLine(out, "501 Syntax error in parameters or arguments");
                } else {
                    mailFrom = email;
                    recipients.clear();
                    writeLine(out, "250 OK");
                }
            } else if (command.equalsIgnoreCase("RCPT TO:")) {
                writeLine(out, "501 Syntax error in parameters or arguments");
            } else if (command.toUpperCase().startsWith("RCPT TO:")) {
                if (mailFrom == null) {
                    writeLine(out, "503 Bad sequence of commands");
                    continue;
                }

                String email = extractEmailArg(command, "RCPT TO:");
                if (email == null) {
                    writeLine(out, "501 Syntax error in parameters or arguments");
                } else if (!userManager.isValidUser(email, serverDomain)) {
                    writeLine(out, "550 Requested action not taken: mailbox unavailable");
                } else {
                    recipients.add(email);
                    writeLine(out, "250 OK");
                }
            } else if (command.equalsIgnoreCase("DATA")) {
                if (recipients.isEmpty()) {
                    writeLine(out, "503 Bad sequence of commands");
                } else {
                    writeLine(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                    inData = true;
                    dataBuffer = new StringBuilder();
                }
            } else if (command.equalsIgnoreCase("QUIT")) {
                writeLine(out, "221 Bye");
                quit = true;
            } else {
                writeLine(out, "501 Syntax error in parameters or arguments");
            }
        }
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        // All SMTP lines must end with \r\n
        out.write((s + "\r\n").getBytes());
        out.flush();
    }

    private String extractArg(String line) {
        int space = line.indexOf(' ');
        if (space == -1) return "";
        return line.substring(space + 1).trim();
    }

    private String extractEmailArg(String line, String prefix) {
        if (!line.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String arg = line.substring(prefix.length()).trim();
        if (arg.isEmpty()) {
            return null;
        }
        if (arg.startsWith("<") && arg.endsWith(">") && arg.length() > 2) {
            arg = arg.substring(1, arg.length() - 1);
        }
        return arg.isEmpty() ? null : arg;
    }
}
