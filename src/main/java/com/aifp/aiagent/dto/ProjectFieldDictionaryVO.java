package com.aifp.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

/**
 * 跨项目字段字典项 VO（Phase 10 Comparison / Ranking / Aggregation）
 * <p>
 * 语义：可访问项目集合内 fieldCode 快照去重投影（form id 升序第一为准），
 * 供 ComparisonSlotExtractor 做字段定位（LLM 只能从字典中选 fieldCode，
 * 后端以此为准做成员校验，追加约束 9/12）。
 *
 * @author Tang_tzb
 */
@Getter
@AllArgsConstructor
public class ProjectFieldDictionaryVO implements Serializable {

    private final String fieldCode;

    /**
     * 字段名称（定义快照，槽位抽取的语义依据）
     */
    private final String fieldName;

    /**
     * 字段类型（FieldType 枚举 code，后端校验可计算性，追加约束 3）
     */
    private final String fieldType;
}
