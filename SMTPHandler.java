import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

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

        while (!quit && (line = in.readLine()) != null) {
            line = line.trim();
            if (line.toUpperCase().startsWith("HELO")) {
                writeLine(out, "250 " + serverDomain + " greets " + extractArg(line));
            } else if (line.equalsIgnoreCase("QUIT")) {
                writeLine(out, "221 Bye");
                quit = true;
            } else {
                // For now, just return 501 syntax error (minimal code list)
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
}
