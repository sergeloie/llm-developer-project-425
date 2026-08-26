package ru.anseranser.mailsender;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

/**
 * Yandex Cloud Function handler for sending emails via SMTP.
 * <p>
 * Called by YaWL workflow via {@code httpCall} step.
 * Expects JSON body: {"to": "...", "subject": "...", "body": "..."}
 * <p>
 * SMTP configuration from environment variables:
 * - SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM
 */
public class EmailSenderFunction implements YcFunction<String, String> {

    private final EmailSender sender;

    public EmailSenderFunction() {
        this.sender = new EmailSender(
                System.getenv("SMTP_HOST"),
                System.getenv("SMTP_PORT"),
                System.getenv("SMTP_USER"),
                System.getenv("SMTP_PASSWORD"),
                System.getenv("SMTP_FROM")
        );
    }

    /**
     * Package-private constructor for unit testing with mocked dependencies.
     */
    EmailSenderFunction(EmailSender sender) {
        this.sender = sender;
    }

    @Override
    public String handle(String input, Context context) {
        try {
            JsonObject body = JsonParser.parseString(input).getAsJsonObject();

            String to = getStringField(body, "to");
            String subject = getStringField(body, "subject");
            String mailBody = getStringField(body, "body");

            if (to == null || to.isBlank()) {
                return errorResponse(400, "Field 'to' is required");
            }

            sender.send(to, subject != null ? subject : "No Subject",
                    mailBody != null ? mailBody : "");

            JsonObject result = new JsonObject();
            result.addProperty("status", "sent");
            result.addProperty("to", to);
            result.addProperty("subject", subject);
            return successResponse(result);

        } catch (IllegalArgumentException e) {
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
            return obj.get(key).getAsString();
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
