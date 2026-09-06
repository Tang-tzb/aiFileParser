package com.aifp.aiagent.parser.pdf.region;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OCR 触发决策器：按区域类型与决策输入综合判定是否区域 OCR。
 * <p>
 * 核心原则：<b>coverageRatio 只是决策输入之一，禁止"覆盖率低 → 直接 OCR"</b>；
 * 不能因为视觉区域 coverage 低就把整个视觉区域送入 OCR。
 * <p>
 * 整页图豁免：占页面积比 ≥ full-page-forbidden-ratio 的区域（整页扫描底图）
 * 恒不区域 OCR——该 OCR 等价于 MIXED 整页 OCR（禁止项）；其缺失文字由
 * 阶段 7/8 表格/表头融合处理。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class OcrEligibilityEvaluator {

    /**
     * IMAGE/UNKNOWN 视觉文本候选阈值：textCandidateScore ≥ 该值才可能 OCR
     */
    @Value("${document.parser.pdf.region.image-text-candidate-ratio:0.60}")
    private double imageTextCandidateRatio = 0.60;

    /**
     * UNKNOWN 判定的覆盖率上限输入（coverage 低于该值视为"缺文字"）
     */
    @Value("${document.parser.pdf.region.text-coverage-skip-ratio:0.50}")
    private double textCoverageSkipRatio = 0.50;

    /**
     * 整页图豁免阈值：区域占页面积比 ≥ 该值 → 恒不 OCR
     */
    @Value("${document.parser.pdf.region.full-page-forbidden-ratio:0.85}")
    private double fullPageForbiddenRatio = 0.85;

    /**
     * 判定区域是否允许 OCR。
     * <p>
     * 默认策略矩阵：TEXT/STAMP/SIGNATURE → false；IMAGE → 默认 false
     * （仅存在明显视觉文本候选才 true）；TABLE（或 likelyTable）→ true；
     * UNKNOWN → 文本候选 + 低覆盖综合判断。
     */
    public boolean shouldOcr(VisualRegion region) {
        if (region == null || region.getBbox() == null) {
            return false;
        }
        // 表格候选标记优先（阶段 7 表格结构识别的数据入口）
        if (region.isLikelyTable()) {
            return true;
        }
        // 整页图豁免：区域级 OCR 整页扫描底图 = 变相整页 OCR（禁止）
        if (region.getPageAreaRatio() >= fullPageForbiddenRatio) {
            log.debug("整页图区域豁免 OCR coverage={}, score={}",
                    region.getCoverageRatio(), region.getTextCandidateScore());
            return false;
        }
        switch (region.getRegionType()) {
            case TABLE:
                return true;
            case IMAGE:
            case UNKNOWN:
                boolean candidate = region.getTextCandidateScore() >= imageTextCandidateRatio;
                if (region.getRegionType() == RegionType.UNKNOWN) {
                    // UNKNOWN 需同时满足"存在视觉文本候选 + 文字覆盖不足"
                    return candidate && region.getCoverageRatio() < textCoverageSkipRatio;
                }
                return candidate;
            case TEXT:
            case STAMP:
            case SIGNATURE:
            default:
                return false;
        }
    }
}
