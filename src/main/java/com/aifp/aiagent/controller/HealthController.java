package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 骨架自检接口
 * <p>
 * 仅用于验证工程是否可启动、统一返回 {@link Result} 与异常链路是否正常，
 * 不属于业务功能。后续阶段可删除或保留。
 *
 * @author aiFileParser
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /** Ping：验证统一返回封装 */
    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "aiFileParser");
        info.put("status", "UP");
        info.put("timestamp", System.currentTimeMillis());
        return Result.success(info);
    }
}
