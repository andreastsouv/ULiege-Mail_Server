import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        OutputStream out = null;
        try {
            out = client.getOutputStream();
            handleSession(out);
        } catch (IOException e) {
            // 421 Service not available, closing transmission channel
            if (out != null) {
                try {
                    writeLine(out, "421 " + serverDomain
                            + " Service not available, closing transmission channel");
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleSession(OutputStream out) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream()));

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
                // DATA mode: collect message until a single dot line
                if (line.equals(".")) {
                    String rawMessage = dataBuffer.toString();
                    try {
                        if (mailFrom == null || recipients.isEmpty()) {
                            // Should not normally happen if we enforce sequences
                            writeLine(out, "451 Requested action aborted: local error in processing");
                        } else {
                            deliverMessage(mailFrom, recipients, rawMessage);
                            writeLine(out, "250 OK");
                        }
                    } catch (IOException e) {
                        writeLine(out, "451 Requested action aborted: local error in processing");
                    }

                    // Reset transaction state
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
            String upper = command.toUpperCase();

            if (upper.startsWith("HELO")) {
                String arg = extractArg(command);
                if (arg.isEmpty()) {
                    writeLine(out, "501 Syntax error in parameters or arguments");
                } else {
                    writeLine(out, "250 " + serverDomain + " greets " + arg);
                    // Reset transaction on new HELO
                    mailFrom = null;
                    recipients.clear();
                }

            } else if (upper.equals("MAIL FROM:")) {
                // Explicit empty argument
                writeLine(out, "501 Syntax error in parameters or arguments");

            } else if (upper.startsWith("MAIL FROM:")) {
                String email = extractEmailArg(command, "MAIL FROM:");
                if (email == null) {
                    writeLine(out, "501 Syntax error in parameters or arguments");
                } else {
                    mailFrom = email;
                    recipients.clear();
                    writeLine(out, "250 OK");
                }

            } else if (upper.equals("RCPT TO:")) {
                writeLine(out, "501 Syntax error in parameters or arguments");

            } else if (upper.startsWith("RCPT TO:")) {
                if (mailFrom == null) {
                    writeLine(out, "503 Bad sequence of commands");
                    continue;
                }

                String email = extractEmailArg(command, "RCPT TO:");
                if (email == null) {
                    // syntactically bad mailbox
                    writeLine(out, "553 Requested action not taken: mailbox name not allowed");
                    continue;
                }

                String domain = getDomainPart(email);
                if (domain == null) {
                    writeLine(out, "553 Requested action not taken: mailbox name not allowed");
                    continue;
                }

                if (domain.equalsIgnoreCase(serverDomain)) {
                    // Local user: must exist
                    if (!userManager.isValidUser(email, serverDomain)) {
                        writeLine(out, "550 Requested action not taken: mailbox unavailable");
                    } else {
                        recipients.add(email);
                        writeLine(out, "250 OK");
                    }
                } else {
                    // Remote domain: accept and later forward via DNS/MX and SMTP
                    recipients.add(email);
                    writeLine(out, "250 OK");
                }

            } else if (upper.equals("DATA")) {
                if (recipients.isEmpty() || mailFrom == null) {
                    writeLine(out, "503 Bad sequence of commands");
                } else {
                    writeLine(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                    inData = true;
                    dataBuffer = new StringBuilder();
                }

            } else if (upper.equals("QUIT")) {
                writeLine(out, "221 Bye");
                quit = true;

            } else {
                // Unknown or unsupported command
                writeLine(out, "501 Syntax error in parameters or arguments");
            }
        }
    }

    /**
     * Deliver a message to local users and forward to remote domains via SMTP/DNS.
     */
    private void deliverMessage(String mailFrom,
                                List<String> recipients,
                                String rawMessage) throws IOException {

        List<String> localRecipients = new ArrayList<>();
        Map<String, List<String>> remoteByDomain = new HashMap<>();

        for (String rcpt : recipients) {
            String domain = getDomainPart(rcpt);
            if (domain == null) {
                continue;
            }
            if (domain.equalsIgnoreCase(serverDomain)) {
                localRecipients.add(rcpt);
            } else {
                List<String> list = remoteByDomain.get(domain);
                if (list == null) {
                    list = new ArrayList<>();
                    remoteByDomain.put(domain, list);
                }
                list.add(rcpt);
            }
        }

        // Local delivery
        for (String rcpt : localRecipients) {
            mailboxManager.storeLocalMessage(rcpt, rawMessage);
        }

        // Remote delivery: one SMTP session per domain
        for (Map.Entry<String, List<String>> entry : remoteByDomain.entrySet()) {
            String domain = entry.getKey();
            List<String> rcptsForDomain = entry.getValue();
            sendToRemoteDomain(mailFrom, domain, rcptsForDomain, rawMessage);
        }
    }

    /**
     * Open an SMTP connection to the remote mail server for the given domain,
     * using DNS MX via `dig +short <domain> MX`, and forward the message.
     */
    private void sendToRemoteDomain(String mailFrom,
                                    String domain,
                                    List<String> domainRecipients,
                                    String message) throws IOException {

        String mxHost = lookupMxHost(domain);
        if (mxHost == null) {
            throw new IOException("No MX host found for domain " + domain);
        }

        Socket smtpSocket = new Socket(mxHost, 25);
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(smtpSocket.getInputStream()));
            OutputStream out = smtpSocket.getOutputStream();

            // Greeting
            String resp = in.readLine();
            if (!isPositiveCompletion(resp)) {
                throw new IOException("Bad greeting from " + mxHost + ": " + resp);
            }

            // HELO
            writeRemoteLine(out, "HELO " + serverDomain);
            resp = in.readLine();
            if (!isPositiveCompletion(resp)) {
                throw new IOException("HELO rejected by " + mxHost + ": " + resp);
            }

            // MAIL FROM
            writeRemoteLine(out, "MAIL FROM:<" + mailFrom + ">");
            resp = in.readLine();
            if (!isPositiveCompletion(resp)) {
                throw new IOException("MAIL FROM rejected by " + mxHost + ": " + resp);
            }

            // RCPT TO for each recipient in this domain
            for (String rcpt : domainRecipients) {
                writeRemoteLine(out, "RCPT TO:<" + rcpt + ">");
                resp = in.readLine();
                if (!isPositiveCompletion(resp)) {
                    throw new IOException("RCPT TO rejected by " + mxHost + ": " + resp);
                }
            }

            // DATA
            writeRemoteLine(out, "DATA");
            resp = in.readLine();
            if (resp == null || resp.length() == 0 || resp.charAt(0) != '3') {
                throw new IOException("DATA rejected by " + mxHost + ": " + resp);
            }

            // Send message body as-is, then terminator line
            out.write(message.getBytes());
            if (!message.endsWith("\r\n")) {
                out.write("\r\n".getBytes());
            }
            out.write(".\r\n".getBytes());
            out.flush();

            resp = in.readLine();
            if (!isPositiveCompletion(resp)) {
                throw new IOException("Message not accepted by " + mxHost + ": " + resp);
            }

            // QUIT
            writeRemoteLine(out, "QUIT");
            // Read and ignore final response
            in.readLine();

        } finally {
            try {
                smtpSocket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Use `dig +short <domain> MX` to find an MX host.
     * Returns the first host found, or null if none.
     */
    private String lookupMxHost(String domain) throws IOException {
        Process process = new ProcessBuilder("dig", "+short", domain, "MX").start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // Typical line: "10 mail.example.com."
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String host = parts[parts.length - 1];
                    if (host.endsWith(".")) {
                        host = host.substring(0, host.length() - 1);
                    }
                    if (!host.isEmpty()) {
                        return host;
                    }
                }
            }
        }
        return null;
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\r\n").getBytes());
        out.flush();
    }

    private void writeRemoteLine(OutputStream out, String s) throws IOException {
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

    private String getDomainPart(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return null;
        }
        return email.substring(at + 1);
    }

    private boolean isPositiveCompletion(String resp) {
        return resp != null && !resp.isEmpty() && resp.charAt(0) == '2';
    }
}
