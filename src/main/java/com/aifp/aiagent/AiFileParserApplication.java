package com.aifp.aiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * AI 文件自动解析自动填报系统 - 启动入口
 * <p>
 * 职责单一：仅负责应用引导、Mapper 扫描与事务开启。
 * 业务装配交给 Spring Boot 自动配置完成。
 *
 * @author aiFileParser
 */
@SpringBootApplication
//@MapperScan("com.aifp.aiagent.repository")
@EnableTransactionManagement
public class AiFileParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiFileParserApplication.class, args);
    }
}
