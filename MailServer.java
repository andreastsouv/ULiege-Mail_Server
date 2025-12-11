import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MailServer {

    private final String domain;
    private final ExecutorService threadPool;
    private final MailboxManager mailboxManager;
    private final UserManager userManager;

    public MailServer(String domain, int maxThreads) {
        this.domain = domain;
        this.threadPool = Executors.newFixedThreadPool(maxThreads);
        this.mailboxManager = new MailboxManager();
        this.userManager = new UserManager();
    }

    public void start() throws IOException {
        // Ports: SMTP 25, POP3 110, IMAP 143
        ServerSocket smtpServer = new ServerSocket(25);
        ServerSocket pop3Server = new ServerSocket(110);
        ServerSocket imapServer = new ServerSocket(143);

        // Accept loops for each protocol
        startAcceptLoop(smtpServer, "SMTP");
        startAcceptLoop(pop3Server, "POP3");
        startAcceptLoop(imapServer, "IMAP");
    }

    private void startAcceptLoop(ServerSocket serverSocket, String protocolName) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    // Dispatch to correct handler
                    Runnable worker = createWorkerForProtocol(protocolName, client);
                    threadPool.execute(worker); // limited thread pool
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();
    }

    private Runnable createWorkerForProtocol(String protocolName, Socket client) {
        if ("SMTP".equals(protocolName)) {
            return new SMTPHandler(client, mailboxManager, userManager, domain);
        } else if ("POP3".equals(protocolName)) {
            return new POP3Handler(client, mailboxManager, userManager, domain);
        } else {
            return new IMAPHandler(client, mailboxManager, userManager, domain);
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java MailServer <domain> <maxThreads>");
            System.exit(1);
        }
        String domain = args[0];
        int maxThreads = Integer.parseInt(args[1]);

        try {
            MailServer server = new MailServer(domain, maxThreads);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
