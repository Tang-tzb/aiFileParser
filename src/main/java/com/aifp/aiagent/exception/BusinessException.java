package com.aifp.aiagent.exception;

import com.aifp.aiagent.common.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常
 * <p>
 * 用于表达可预期的业务错误，由全局异常处理器捕获并转换为统一响应。
 * 抛出方式：
 * <pre>
 *   throw new BusinessException(ResultCode.FILE_NOT_FOUND);
 *   throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "Qwen 调用超时");
 * </pre>
 *
 * @author aiFileParser
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码，对应 {@link ResultCode#getCode()} */
    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.getCode();
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
    }
}
