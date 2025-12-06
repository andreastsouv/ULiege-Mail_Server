import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class POP3Handler implements Runnable {

    private final Socket client;
    private final MailboxManager mailboxManager;
    private final UserManager userManager;
    private final String serverDomain;

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
        // For now, just greet and close; you’ll implement full POP3 later.
        writeLine(out, "+OK POP3 server ready (" + serverDomain + ")");
        writeLine(out, "+OK Goodbye");
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\r\n").getBytes());
        out.flush();
    }
}
