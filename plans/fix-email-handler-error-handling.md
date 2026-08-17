# Fix EmailHandler Error Handling

## Problem

In [`EmailHandler.handle()`](../helpdesk/src/main/java/ru/anseranser/mail/EmailHandler.java:49), a single `try` block wraps both IMAP connection/fetch operations and per-message processing (extract, agent call, send, markAsSeen). The catch blocks produce misleading log messages:

| Operation | Exception Type | Current Catch Message | Problem |
|-----------|---------------|----------------------|---------|
| `sender.send()` | `MessagingException` | `"Failed to fetch unread emails from IMAP server"` | Misleading — it was a **send** error, not a fetch error |
| `receiver.markAsSeen()` | `MessagingException` | `"Failed to fetch unread emails from IMAP server"` | Same issue |
| `agent.getResponse()` | Unchecked exceptions | **Not caught at all** | Crashes the entire loop |
| `sender.send()` failure | — | Loop aborts | Remaining messages are never processed |

## Solution

Restructure the `handle()` method with **two levels of try-catch**:

1. **Outer try-catch** — covers only IMAP connection + fetch operations
2. **Inner try-catch** inside the `for` loop — covers per-message processing with distinct catch blocks for each failure mode

### Proposed Structure

```java
public String handle(String s, Context context) {
    int mailCount = 0;
    try {
        receiver.connect();
        Message[] messages = receiver.fetchUnreadMessages();

        if (messages != null) {
            for (Message message : messages) {
                mailCount += processMessage(message);
            }
        }
    } catch (MessagingException e) {
        System.out.println("Failed to connect or fetch emails from IMAP server: " + e.getMessage());
    }
    return mailCount + " mail(s) done";
}

private int processMessage(Message message) {
    try {
        String from = getAddress(message.getFrom()[0]);
        String body = extractor.extractPlainText(message);
        if (body == null || body.isBlank()) {
            System.out.println("Skipping email with empty or unreadable content from: " + from);
            return 0;
        }
        String response = agent.getResponse(body);
        sender.send(from, "Agent answer", response);
        receiver.markAsSeen(message);
        return 1;
    } catch (MessagingException e) {
        System.out.println("Failed to send reply email to " + ": " + e.getMessage());
        return 0;
    } catch (IOException e) {
        System.out.println("Failed to parse email content: " + e.getMessage());
        return 0;
    } catch (Exception e) {
        System.out.println("Failed to process email: " + e.getMessage());
        return 0;
    }
}
```

### Key Changes

1. **Extract per-message logic into [`processMessage()`](../helpdesk/src/main/java/ru/anseranser/mail/EmailHandler.java) method** — isolates error handling scope
2. **Outer catch only handles IMAP connection/fetch** — message clearly says `"Failed to connect or fetch emails from IMAP server"`
3. **Inner catches are specific**:
   - `MessagingException` → `"Failed to send reply email to ..."` (covers send + markAsSeen)
   - `IOException` → `"Failed to parse email content"`
   - `Exception` → `"Failed to process email"` (covers agent errors, null pointer, etc.)
4. **Failed message does not abort the loop** — `processMessage()` catches all exceptions and returns 0, so the loop continues with the next message
5. **`mailCount` only increments on success** — the return value accurately reflects processed messages

### Behavior Changes

| Scenario | Before | After |
|----------|--------|-------|
| `send()` fails for message 2 of 3 | Loop aborts, message 3 never processed | Message 2 skipped, message 3 still processed |
| `agent.getResponse()` throws | Unhandled exception crashes handler | Caught, logged, skipped gracefully |
| `send()` `MessagingException` | Log says "Failed to fetch" | Log says "Failed to send reply" |
| `markAsSeen()` fails | Log says "Failed to fetch" | Log says "Failed to send reply" |

### Files to Modify

- [`EmailHandler.java`](../helpdesk/src/main/java/ru/anseranser/mail/EmailHandler.java) — extract `processMessage()` method, restructure try-catch
- [`EmailHandlerTest.java`](../helpdesk/src/test/java/ru/anseranser/mail/EmailHandlerTest.java) — add unit tests for error handling paths using mocks

### Testing Plan

Add mock-based unit tests (currently the test file only has integration tests):

1. Test that `MessagingException` from `sender.send()` produces correct log message and does not abort the loop
2. Test that unchecked exception from `agent.getResponse()` is caught and logged
3. Test that `IOException` from `extractor.extractPlainText()` produces correct log message
4. Test that `MessagingException` from `receiver.fetchUnreadMessages()` produces correct outer-level log message
5. Test that `mailCount` is accurate — only counts successfully processed messages
