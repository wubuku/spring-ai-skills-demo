package com.example.demo.model;

import lombok.Data;
import java.util.Map;

/**
 * API 结果解释请求
 */
@Data
public class ExplainRequest {
    /** HTTP 方法 */
    private String method;

    /** API 路径（不含查询参数） */
    private String url;

    /** 查询参数 */
    private Map<String, String> queryParams;

    /** HTTP 状态码 */
    private int statusCode;

    /** 响应体（JSON 字符串） */
    private String responseBody;
}
