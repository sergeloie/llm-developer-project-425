package ru.anseranser.ydb;

/** Combined result: resolved action + the body JSON string ready for the handler. */
public record DispatchResult(Action action, String body) {}
