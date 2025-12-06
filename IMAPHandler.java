import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class IMAPHandler implements Runnable {

    private final Socket client;
    private final MailboxManager mailboxManager;
    private final UserManager userManager;
    private final String serverDomain;

    public IMAPHandler(Socket client,
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
        // Minimal IMAP greeting; you’ll later implement CAPABILITY/LOGIN/etc.
        writeLine(out, "* OK IMAP server ready (" + serverDomain + ")");
        // For now, immediately close with a fake tag
        writeLine(out, "A1 OK LOGOUT completed");
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\r\n").getBytes());
        out.flush();
    }
}
