package ru.anseranser.mailsender;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

/**
 * Yandex Cloud Function handler for sending emails via SMTP.
 * <p>
 * Called by YaWL workflow via {@code httpCall} step.
 * Expects JSON body: {"subject": "...", "body": "..."}
 * <p>
 * The recipient address is taken from the {@code HELPDESK_MAILBOX} environment variable.
 * <p>
 * SMTP configuration from environment variables:
 * - SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM
 */
public class EmailSenderFunction implements YcFunction<String, String> {

    private final EmailSender sender;
    private final String helpdeskMailbox;

    public EmailSenderFunction() {
        this.sender = new EmailSender(
                System.getenv("SMTP_HOST"),
                System.getenv("SMTP_PORT"),
                System.getenv("SMTP_USER"),
                System.getenv("SMTP_PASSWORD"),
                System.getenv("SMTP_USER")
        );
        this.helpdeskMailbox = System.getenv("HELPDESK_MAILBOX");
    }

    /**
     * Package-private constructor for unit testing with mocked dependencies.
     */
    EmailSenderFunction(EmailSender sender, String helpdeskMailbox) {
        this.sender = sender;
        this.helpdeskMailbox = helpdeskMailbox;
    }

    @Override
    public String handle(String input, Context context) {
        if (input == null) {
            return errorResponse(400, "Invalid request body: null input");
        }

        try {
            JsonObject body = JsonParser.parseString(input).getAsJsonObject();

            String to = helpdeskMailbox;
            String subject = getStringField(body, "subject");
            String mailBody = getStringField(body, "body");

            if (to == null || to.isBlank()) {
                return errorResponse(500, "Environment variable HELPDESK_MAILBOX is not set");
            }

            sender.send(to, subject != null ? subject : "No Subject",
                    mailBody != null ? mailBody : "");

            JsonObject result = new JsonObject();
            result.addProperty("status", "sent");
            result.addProperty("to", to);
            result.addProperty("subject", subject);
            return successResponse(result);

        } catch (IllegalArgumentException | IllegalStateException | JsonSyntaxException e) {
            return errorResponse(400, "Invalid request body: " + e.getMessage());
        } catch (jakarta.mail.MessagingException e) {
            System.out.println("SMTP error: " + e.getMessage());
            return errorResponse(502, "SMTP error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            return errorResponse(500, "Unexpected error: " + e.getMessage());
        }
    }

    private String getStringField(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            com.google.gson.JsonElement element = obj.get(key);
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
            // For arrays and objects, serialize to pretty-printed JSON
            return new Gson().newBuilder().setPrettyPrinting().create().toJson(element);
        }
        return null;
    }

    private String successResponse(JsonObject body) {
        return "{\"statusCode\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":"
                + new Gson().toJson(body) + "}";
    }

    private String errorResponse(int statusCode, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return "{\"statusCode\":" + statusCode
                + ",\"headers\":{\"Content-Type\":\"application/json\"},\"body\":"
                + new Gson().toJson(error) + "}";
    }
}
