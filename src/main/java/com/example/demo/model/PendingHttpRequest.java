package com.example.demo.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已通过 Skill API index 校验、等待用户确认的写操作。
 *
 * 认证信息不属于待确认元数据；浏览器在用户点击确认时读取当前 token。
 */
public record PendingHttpRequest(
    String method,
    String url,
    Map<String, String> queryParams,
    Map<String, Object> body
) {

    public PendingHttpRequest {
        method = method == null ? "" : method;
        url = url == null ? "" : url;
        queryParams = immutableStringMap(queryParams);
        body = immutableObjectMap(body);
    }

    private static Map<String, String> immutableStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
