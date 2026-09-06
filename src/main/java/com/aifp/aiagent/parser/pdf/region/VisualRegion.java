package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

/**
 * 视觉区域：MIXED 页融合的空间决策单元（坐标恒为 PDF 用户空间）。
 * <p>
 * 字段同时承载区域分类结果与 OCR 决策输入（coverageRatio/textCandidateScore
 * 仅为 {@link OcrEligibilityEvaluator} 的输入之一，不单独决定是否 OCR）。
 * 表格候选信息（likelyTable/tableScore）供阶段 7 表格结构识别消费。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class VisualRegion {

    /**
     * 区域类型（六型）
     */
    private RegionType regionType;

    /**
     * 区域外接矩形（PDF 用户空间，来自图片占位 CTM 或文字网格并集）
     */
    private BoundingBox bbox;

    /**
     * 表格候选标记：true 表示"该区域可能是表格"，<b>仅候选标记</b>，
     * 不代表已完成表格识别（阶段 7 负责表格线检测与 Cell 恢复）
     */
    private boolean likelyTable;

    /**
     * 表格候选分（0~1，原生文字网格对齐列数归一）
     */
    private double tableScore;

    /**
     * 文字覆盖率：文字块与区域交集并集面积 / 区域面积（OCR 决策输入之一）
     */
    private double coverageRatio;

    /**
     * 视觉文本候选分（0~1）：区域裁剪图行投影文本带得分（OCR 决策输入之一）
     */
    private double textCandidateScore;

    /**
     * 区域占页面积比（区域面积/页面面积；整页图豁免判定输入）
     */
    private double pageAreaRatio;

    /**
     * 分类依据与决策说明（溯源）
     */
    private String description;
}
