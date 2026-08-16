package ru.anseranser.ydb;

import java.util.Map;

public record QueryParams(String yql, Map<String, Object> params) {
}
