# Plan: Refactor YDB package for Cloud Function deployment

## Problem Summary

Classes in `ru.anseranser.ydb` have multiple constructors and redundant JSON parsing logic that:
- Prevents compilation (test constructor is commented out but referenced by tests)
- Creates ambiguity for Cloud Function runtime
- Causes the same JSON to be parsed 3 times per invocation

## Files affected

| File | Issue |
|------|-------|
| [`YdbClient.java`](helpdesk/src/main/java/ru/anseranser/ydb/YdbClient.java) | Commented-out test constructor; only 1 public constructor for Cloud Function |
| [`YdbTicketsHandler.java`](helpdesk/src/main/java/ru/anseranser/ydb/YdbTicketsHandler.java) | 2 constructors: public no-arg + package-private for tests |
| [`EventDispatcher.java`](helpdesk/src/main/java/ru/anseranser/ydb/EventDispatcher.java) | Redundant JSON parsing in dispatch flow |
| [`YdbClientTest.java`](helpdesk/src/test/java/ru/anseranser/ydb/YdbClientTest.java) | References non-existent constructor |
| [`YdbTicketsHandlerTest.java`](helpdesk/src/test/java/ru/anseranser/ydb/YdbTicketsHandlerTest.java) | Uses package-private constructor |

Classes with no issues: [`QueryParams`](helpdesk/src/main/java/ru/anseranser/ydb/QueryParams.java) (record), [`EventDispatcherTest`](helpdesk/src/test/java/ru/anseranser/ydb/EventDispatcherTest.java).

---

## Refactoring Steps

### Step 1: YdbClient — single public constructor for Cloud Function

**Current state**: 1 public constructor `(String, String, String)` + 1 commented-out test constructor `(GrpcTransport, TableClient)`.

**Change**: Uncomment the test constructor but make it **package-private** (no `public` modifier). This:
- Fixes compilation of `YdbClientTest`
- Keeps the public API clean (Cloud Function only sees the 3-arg constructor)
- Allows tests in the same package to inject mocks

```java
// Public — for Cloud Function
public YdbClient(String endpoint, String database, String token) { ... }

// Package-private — for tests only
YdbClient(GrpcTransport transport, TableClient tableClient) { ... }
```

No other changes to `YdbClient` are needed.

### Step 2: YdbTicketsHandler — eliminate second constructor, use static factory for tests

**Current state**: 2 constructors.
- `public YdbTicketsHandler()` — creates EventDispatcher internally
- `YdbTicketsHandler(YdbClient, EventDispatcher)` — package-private, for tests

**Change**: Merge into a single public no-arg constructor. For testability, add a **package-private static factory method** `createForTest(YdbClient, EventDispatcher)` that sets the fields directly. This is cleaner than a second constructor and makes the intent explicit.

```java
public YdbTicketsHandler() {
    this.dispatcher = new EventDispatcher();
}

// For tests only
static YdbTicketsHandler createForTest(YdbClient ydbClient, EventDispatcher dispatcher) {
    YdbTicketsHandler handler = new YdbTicketsHandler();
    handler.ydbClient = ydbClient;
    handler.dispatcher = dispatcher;
    return handler;
}
```

The `dispatcher` field becomes non-final (or set in both paths). The `ydbClient` field is already `volatile`.

### Step 3: EventDispatcher — return parsed body along with action to avoid re-parsing

**Current state**: 
- `dispatch(String event)` parses JSON, determines action
- `extractBody(String event)` parses JSON again, extracts body string
- `YdbTicketsHandler.handle()` calls both → JSON parsed 3 times total (dispatch parses event, dispatchFromGateway parses inner body, handle parses body again)

**Change**: Add a new method `dispatchAndExtract(String event)` that returns both the action and the body in one parse. Replace the existing two methods with a single call.

```java
public record DispatchResult(Action action, String body) {}

public DispatchResult dispatchAndExtract(String event) {
    JsonObject json = parseEvent(event);
    Action action;
    String body;

    if (json.has("httpMethod")) {
        body = extractBodyFromJson(json);
        action = dispatchFromMcpHub(parseEvent(body));
    } else if (json.has("action")) {
        body = json.toString();
        action = dispatchFromDirectInvoke(json);
    } else {
        body = json.toString();
        action = dispatchFromMcpHub(json);
    }

    return new DispatchResult(action, body);
}
```

Remove the old `dispatch()` and `extractBody()` methods (or keep them if backward compatibility is needed, but they are only used internally).

### Step 4: YdbTicketsHandler.handle() — use new single-call API

**Current state**:
```java
EventDispatcher.Action action = dispatcher.dispatch(s);
String body = dispatcher.extractBody(s);
JsonObject json = JsonParser.parseString(body).getAsJsonObject();
```

**Change**:
```java
EventDispatcher.DispatchResult result = dispatcher.dispatchAndExtract(s);
JsonObject json = JsonParser.parseString(result.body()).getAsJsonObject();

return switch (result.action()) {
    case CREATE_TICKET -> client.createTicket(...);
    case LIST_MY_TICKETS -> client.listMyTickets(...);
    case APPEND_MESSAGE -> client.appendMessage(...);
};
```

### Step 5: Update tests

- **[`YdbClientTest`](helpdesk/src/test/java/ru/anseranser/ydb/YdbClientTest.java:52)**: No change needed — it already calls `new YdbClient(transport, tableClient)` which will now compile with the package-private constructor.

- **[`YdbTicketsHandlerTest`](helpdesk/src/test/java/ru/anseranser/ydb/YdbTicketsHandlerTest.java:25)**: Replace `new YdbTicketsHandler(ydbClient, dispatcher)` with `YdbTicketsHandler.createForTest(ydbClient, dispatcher)`.

- **[`EventDispatcherTest`](helpdesk/src/test/java/ru/anseranser/ydb/EventDispatcherTest.java)**: Update tests that call `dispatch()` and `extractBody()` separately to use `dispatchAndExtract()`. Add a few new tests for the combined method.

### Step 6: Verify compilation and tests

Run `mvn compile test` to verify everything compiles and all tests pass.

---

## Mermaid Diagram: Before vs After

```
flowchart TD
    subgraph Before
    A1[Cloud Function invokes handle] --> B1[dispatch parses JSON]
    B1 --> C1[extractBody parses JSON again]
    C1 --> D1[JsonParser.parseString parses body]
    B1 --> |API Gateway| E1[dispatchFromGateway re-parses inner body]
    end

    subgraph After
    A2[Cloud Function invokes handle] --> B2[dispatchAndExtract parses JSON once]
    B2 --> C2[Returns action + body string]
    C2 --> D2[JsonParser.parseString parses body only]
    end
```

---

## Risks and Considerations

1. **Cloud Function no-arg constructor requirement**: `YdbTicketsHandler` will keep exactly one public no-arg constructor — fully compatible with `YcFunction` contract.
2. **Thread safety**: The `ydbClient` lazy init with `volatile` + `synchronized` remains unchanged.
3. **Test backward compatibility**: All existing test assertions remain valid; only constructor/factory calls change.
4. **`EventDispatcher` API change**: Removing `dispatch()` and `extractBody()` is safe since they are only used internally in `YdbTicketsHandler`.
