package ru.anseranser.prod;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;

import java.util.Properties;

/**
 * Manages IMAP connection lifecycle and provides message fetching.
 */
public class EmailReceiver {

    private final String host;
    private final int port;
    private final String user;
    private final String password;

    private Session session;
    private Store store;
    private Folder inbox;

    public EmailReceiver(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    /**
     * Ensures the IMAP connection is established and the INBOX folder is open.
     */
    public void connect() throws MessagingException {
        if (store != null && store.isConnected() && inbox != null && inbox.isOpen()) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.host", host);
        properties.setProperty("mail.imaps.port", String.valueOf(port));
        properties.setProperty("mail.imaps.ssl.enable", "true");

        session = Session.getInstance(properties);
        store = session.getStore("imaps");
        store.connect(host, port, user, password);

        inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
    }

    /**
     * Fetches all unread messages from the INBOX.
     *
     * @return array of unread messages
     */
    public Message[] fetchUnreadMessages() throws MessagingException {
        return inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
    }

    /**
     * Marks a message as seen (read).
     *
     * @param message the message to mark
     */
    public void markAsSeen(Message message) throws MessagingException {
        message.setFlag(Flags.Flag.SEEN, true);
    }

    /**
     * Closes the IMAP folder and store connections.
     */
    public void disconnect() {
        closeQuietly(inbox);
        closeQuietly(store);
        inbox = null;
        store = null;
        session = null;
    }

    private void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(true);
            } catch (MessagingException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                System.out.println(e.getMessage());
            }
        }
    }
}
