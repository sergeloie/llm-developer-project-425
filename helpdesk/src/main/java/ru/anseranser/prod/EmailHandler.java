package ru.anseranser.prod;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponsePrompt;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import org.jsoup.Jsoup;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

import java.io.IOException;
import java.util.Properties;

public class EmailHandler implements YcFunction<String, String> {

    private final String IMAP_HOST;
    private final Integer IMAP_PORT;
    private final String IMAP_USER;
    private final String IMAP_PASSWORD;
    private final String SMTP_HOST;
    private final String SMTP_PORT;
    private final String SMTP_USER;
    private final String SMTP_PASSWORD;
    private final String HELPDESK_MAILBOX;
    private final String AGENT_ID;
    private final String ORGANIZATION_ID;
    private final String YANDEX_API_KEY;

    private Session session;
    private Store store;
    private Folder inbox;

    public EmailHandler() {
        this.IMAP_HOST = System.getenv("IMAP_HOST");
        this.IMAP_PORT = 993;
        this.IMAP_USER = System.getenv("IMAP_USER");
        this.IMAP_PASSWORD = System.getenv("IMAP_PASSWORD");
        this.SMTP_HOST = System.getenv("SMTP_HOST");
        this.SMTP_PORT = System.getenv("SMTP_PORT");
        this.SMTP_USER = System.getenv("SMTP_USER");
        this.SMTP_PASSWORD = System.getenv("SMTP_PASSWORD");
        this.HELPDESK_MAILBOX = System.getenv("HELPDESK_MAILBOX");
        this.AGENT_ID = System.getenv("AGENT_ID");
        this.ORGANIZATION_ID = System.getenv("ORGANIZATION_ID");
        this.YANDEX_API_KEY = System.getenv("YANDEX_API_KEY");
    }

    @Override
    public String handle(String s, Context context) {
        int mailCount = 0;
        try {
            ensureConnected();
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            if (messages != null) {
                for (Message message : messages) {
                    String from = getAddress(message.getFrom()[0]);
                    String body = Jsoup.parse(message.getContent().toString()).text();
                    String response = getAgentResponse(body);
                    sendEmail(from, "Agent answer", response);
                    message.setFlag(Flags.Flag.SEEN, true);
                    mailCount++;
                }
            }
        } catch (MessagingException e) {
            System.out.println("Failed to fetch unread emails from IMAP server: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("Failed parse email content: " + e.getMessage());

        }
        return mailCount + " mail(s) done";
    }


    private String getAgentResponse(String request) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(YANDEX_API_KEY)
                .baseUrl("https://ai.api.cloud.yandex.net/v1")
                .organization(ORGANIZATION_ID)
                .build();

        ResponseCreateParams params = ResponseCreateParams.builder()
                .prompt(ResponsePrompt.builder()
                        .id(AGENT_ID)
                        .build())
                .input(request)
                .build();

        Response response = client.responses().create(params);
        String modelResponse = response.output().getFirst().message().get().content().getFirst().asOutputText().text();

        System.out.printf("Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d.%s",
                response.id(),
                request,
                modelResponse,
                response.usage().get().inputTokens(),
                response.usage().get().outputTokens(),
                System.lineSeparator());

        return modelResponse;
    }

    private void sendEmail(String to, String subject, String text) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
//        props.put("mail.debug", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USER, SMTP_PASSWORD);
            }
        });
        session.setDebugOut(System.out);

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(HELPDESK_MAILBOX));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setText(text);

        Transport.send(message);
    }

    /**
     * Ensures the IMAP connection is established and the INBOX folder is open.
     */
    private void ensureConnected() throws MessagingException {
        if (store != null && store.isConnected() && inbox != null && inbox.isOpen()) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.host", IMAP_HOST);
        properties.setProperty("mail.imaps.port", String.valueOf(IMAP_PORT));
        properties.setProperty("mail.imaps.ssl.enable", "true");

        session = Session.getInstance(properties);
        store = session.getStore("imaps");
        store.connect(IMAP_HOST, IMAP_PORT, IMAP_USER, IMAP_PASSWORD);

        inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
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

    private String getAddress(Address address) {
        return ((InternetAddress) address).getAddress();
    }

    private String getPersonal(Address address) {
        return ((InternetAddress) address).getPersonal();
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
