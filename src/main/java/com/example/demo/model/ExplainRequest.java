package com.example.demo.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.Map;

/**
 * API 结果解释请求
 */
@Data
public class ExplainRequest {
    /** HTTP 方法 */
    @NotBlank(message = "method 不能为空")
    private String method;

    /** API 路径（不含查询参数） */
    @NotBlank(message = "url 不能为空")
    @Size(max = 2048, message = "url 长度不能超过 2048")
    private String url;

    /** 查询参数 */
    private Map<String, String> queryParams;

    /** HTTP 状态码 */
    @Min(value = 100, message = "statusCode 必须是有效 HTTP 状态码")
    @Max(value = 599, message = "statusCode 必须是有效 HTTP 状态码")
    private int statusCode;

    /** 响应体（JSON 字符串） */
    @Size(max = 32768, message = "responseBody 长度不能超过 32768")
    private String responseBody;
}
