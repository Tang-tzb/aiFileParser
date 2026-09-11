package com.aifp.aiagent.entity;

import com.aifp.aiagent.entity.enums.FieldType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 项目表单字段值实体
 * <p>
 * 对应表 project_form_field_value：项目结构化事实（区别于 Milvus 中的非结构化文档知识）。
 * {@code fieldCode/fieldName/fieldType} 为字段定义快照——定义变更不影响历史值；
 * {@code rawValue} 为原始抽取文本，{@code normalizedValue} 为标准化值（万/亿换算、日期 ISO），
 * 用于排序与比较；来源追溯字段（sourceFileId/sourcePage/sourceChunkId）用于回答事实时提供出处。
 * 写入方为 Phase 4 ProjectFormPersistenceService，本阶段仅提供数据模型。
 *
 * @author Tang_tzb
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_form_field_value")
public class ProjectFormFieldValue extends BaseEntity {

    /**
     * 项目表单实例ID（project_form）
     */
    private Long projectFormId;

    /**
     * 字段定义ID（form_field_definition）
     */
    private Long fieldId;

    /**
     * 字段编码（定义快照）
     */
    private String fieldCode;

    /**
     * 字段名称（定义快照）
     */
    private String fieldName;

    /**
     * 字段类型（定义快照）
     */
    private FieldType fieldType;

    /**
     * 原始抽取值
     */
    private String rawValue;

    /**
     * 标准化值（数值类经万/亿换算、日期转 ISO；统一文本存储，按字段类型解释）
     */
    private String normalizedValue;

    /**
     * 单位（如 万元、平方米）
     */
    private String unit;

    /**
     * 来源文件ID（file_record）
     */
    private Long sourceFileId;

    /**
     * 来源 ChunkID
     */
    private String sourceChunkId;

    /**
     * 来源页码
     */
    private Integer sourcePage;

    /**
     * 抽取置信度（0~1）
     */
    private BigDecimal confidence;
}
