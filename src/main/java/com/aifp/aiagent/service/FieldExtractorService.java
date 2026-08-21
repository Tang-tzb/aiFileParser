package com.aifp.aiagent.service;

import java.util.Map;

/**
 * AI 自动填表核心服务
 * <p>
 * 输入 formId + fileId，根据表单字段定义动态生成查询，从 Milvus 检索相关切片，
 * 单次调用 Qwen-Plus 抽取全字段并以 Map 返回。字段变化无需修改代码。
 *
 * @author Tang_tzb
 */
public interface FieldExtractorService {

    /**
     * 根据 formId 的字段定义，从 fileId 对应文档中抽取字段值。
     *
     * @param formId 表单ID
     * @param fileId 文件记录ID
     * @return 字段值 Map，key=fieldCode，value=字段值
     */
    Map<String, Object> extract(Long formId, Long fileId);
}
