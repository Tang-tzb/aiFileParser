package com.aifp.aiagent.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局响应状态码
 * <p>
 * 编码规则：
 * - 2xx：成功
 * - 4xx：客户端错误（参数/权限/资源）
 * - 5xx：服务端错误
 * - 1xxx：业务自定义错误（按模块段划分）
 * - 2xxx：文件解析模块
 * - 3xxx：AI 调用模块
 * - 4xxx：向量检索模块
 *
 * @author aiFileParser
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    PARAM_ERROR(400, "参数错误"),
    PARAM_VALID_ERROR(40001, "参数校验失败"),
    UNAUTHORIZED(401, "未授权访问"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),

    INTERNAL_ERROR(500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // 业务模块段：2xxx 文件解析
    FILE_PARSE_ERROR(2001, "文件解析失败"),
    FILE_TYPE_NOT_SUPPORT(2002, "不支持的文件类型"),
    FILE_UPLOAD_ERROR(2003, "文件上传失败"),
    FILE_NOT_FOUND(2004, "文件不存在"),

    // 业务模块段：3xxx AI 调用
    AI_INVOKE_ERROR(3001, "AI 模型调用失败"),
    AI_RESPONSE_PARSE_ERROR(3002, "AI 响应解析失败"),

    // 业务模块段：4xxx 向量检索
    VECTOR_STORE_ERROR(4001, "向量存储操作失败"),
    VECTOR_RETRIEVE_ERROR(4002, "向量检索失败");

    private final Integer code;
    private final String message;

}
