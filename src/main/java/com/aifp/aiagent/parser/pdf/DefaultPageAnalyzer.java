package com.aifp.aiagent.parser.pdf;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 默认页面内容分析器
 * <p>
 * 核心设计（对应《PDF解析改造方案》第四节）：
 * <ul>
 *   <li>文字统计：PDFTextStripper 子类逐 {@link TextPosition} 统计非空白字符数与覆盖面积；</li>
 *   <li>图片统计：PDFStreamEngine 子类经 CTM 计算图片实际占位矩形与页面的有效覆盖比，
 *       而非简单 imageCount>0，避免 Logo/印章小图干扰；</li>
 *   <li>类型判定：文字是否有效 + 是否存在大图 + 是否存在图片 三要素分类矩阵；</li>
 *   <li>统计异常降级：图片统计失败仅告警，不阻断检测（方案第三十一节）。</li>
 * </ul>
 * 注：Inline 图片（BI/ID/EI 直接内嵌于内容流）本阶段不统计，常规扫描/图片 PDF 均为 XObject 形式。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class DefaultPageAnalyzer implements PageAnalyzer {

    /**
     * 整页图片判定阈值：单图有效覆盖比 >= 该值视为整页图
     */
    @Value("${document.parser.pdf.detector.full-image-ratio:0.85}")
    private double fullImageRatio;

    /**
     * 大图判定阈值：单图有效覆盖比 >= 该值视为大图（触发 MIXED）；小 Logo/印章不受影响
     */
    @Value("${document.parser.pdf.detector.large-image-ratio:0.20}")
    private double largeImageRatio;

    /**
     * 有效文字最小字符数：低于该值视为无有效文字层
     */
    @Value("${document.parser.pdf.detector.text-min-count:5}")
    private int textMinCount;

    /**
     * 三要素分类矩阵（包级可见，便于阈值边界单测）。
     * <pre>
     * 无有效文字 & 无图片      → EMPTY
     * 无有效文字 & 有图片      → IMAGE_ONLY
     * 有有效文字 & 有大图      → MIXED
     * 有有效文字 & 无大图      → TEXT_ONLY（仅小 Logo/印章时不误判）
     * </pre>
     */
    static PageContentType classify(boolean hasMeaningfulText, boolean hasLargeImage, boolean hasImages) {
        if (!hasMeaningfulText && !hasImages) {
            return PageContentType.EMPTY;
        }
        if (!hasMeaningfulText) {
            return PageContentType.IMAGE_ONLY;
        }
        if (hasLargeImage) {
            return PageContentType.MIXED;
        }
        return PageContentType.TEXT_ONLY;
    }

    @Override
    public PageProfile analyze(PDDocument document, int pageIndex) {
        PDPage page = document.getPage(pageIndex);
        PDRectangle box = page.getMediaBox();
        double pageArea = (double) box.getWidth() * box.getHeight();

        TextStats text = collectText(document, pageIndex);
        ImageStats image = collectImages(page, pageIndex + 1);

        boolean hasMeaningfulText = text.count >= textMinCount;
        boolean hasFullPageImage = image.maxRatio >= fullImageRatio;
        boolean hasLargeImage = image.maxRatio >= largeImageRatio;

        return PageProfile.builder()
                .pageNumber(pageIndex + 1)
                .pageWidth(box.getWidth())
                .pageHeight(box.getHeight())
                .textCount(text.count)
                .imageCount(image.count)
                .textAreaRatio(pageArea > 0 ? text.area / pageArea : 0)
                .imageAreaRatio(pageArea > 0 ? image.totalArea / pageArea : 0)
                .hasFullPageImage(hasFullPageImage)
                .hasLargeImage(hasLargeImage)
                .hasTableLikeRegion(false)
                .contentType(classify(hasMeaningfulText, hasLargeImage, image.count > 0))
                .build();
    }

    /**
     * 按页统计文字：非空白字符数 + TextPosition 覆盖面积。
     */
    private TextStats collectText(PDDocument document, int pageIndex) {
        try {
            PositionalTextStripper stripper = new PositionalTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(document);
            return stripper.stats;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "PDF 文字统计失败: 第" + (pageIndex + 1) + "页");
        }
    }

    /**
     * 统计页面图片占位；失败时降级为已统计部分并告警，不阻断检测。
     */
    private ImageStats collectImages(PDPage page, int pageNumber) {
        ImageAreaEngine engine = new ImageAreaEngine(page.getMediaBox());
        try {
            engine.processPage(page);
        } catch (Exception e) {
            log.warn("页面图片统计降级 pageNumber={}, 已统计 imageCount={}, 原因: {}",
                    pageNumber, engine.stats.count, e.getMessage());
        }
        return engine.stats;
    }

    /**
     * 文字统计值（内部传递）
     */
    private static class TextStats {
        int count;
        double area;
    }

    /**
     * 图片统计值（内部传递）
     */
    private static class ImageStats {
        int count;
        double totalArea;
        double maxRatio;
    }

    /**
     * 带位置的文字抽取器：逐 TextPosition 累计非空白字符数与覆盖面积。
     */
    private static class PositionalTextStripper extends PDFTextStripper {
        private final TextStats stats = new TextStats();

        PositionalTextStripper() throws IOException {
            // 按位置排序，保证文字流顺序与版面一致
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            for (TextPosition pos : positions) {
                String unicode = pos.getUnicode();
                if (unicode == null) {
                    continue;
                }
                for (int i = 0; i < unicode.length(); i++) {
                    if (!Character.isWhitespace(unicode.charAt(i))) {
                        stats.count++;
                    }
                }
                stats.area += (double) pos.getWidth() * pos.getHeight();
            }
        }
    }

    /**
     * 图片占位统计引擎：经 CTM 换算图片实际占位矩形，与页面矩形求有效覆盖面积。
     * <p>
     * 坐标系为 PDF 用户空间（原点左下角，单位 pt）；近似假设页面矩形为 (0,0)-(w,h)，
     * 精确坐标统一转换由阶段 5 CoordinateTransformer 承担。
     */
    private static class ImageAreaEngine extends PDFStreamEngine {
        private final ImageStats stats = new ImageStats();
        private final double pageWidth;
        private final double pageHeight;
        private final double pageArea;

        ImageAreaEngine(PDRectangle box) {
            this.pageWidth = box.getWidth();
            this.pageHeight = box.getHeight();
            this.pageArea = pageWidth * pageHeight;
            addOperator(new Concatenate(this));
            addOperator(new DrawObject(this));
            addOperator(new SetGraphicsStateParameters(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName())) {
                COSBase base = operands.isEmpty() ? null : operands.get(0);
                if (base instanceof COSName name) {
                    PDXObject xobject = getResources().getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        // 图片 XObject：按当前变换矩阵记录占位
                        recordImage(getGraphicsState().getCurrentTransformationMatrix());
                    } else if (xobject instanceof PDFormXObject form) {
                        // Form XObject：递归展开内部内容流
                        showForm(form);
                    }
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        /**
         * 计算图片占位矩形与页面的交叠面积，累计占比与最大单图占比。
         */
        private void recordImage(org.apache.pdfbox.util.Matrix ctm) {
            stats.count++;
            double w = Math.abs(ctm.getScalingFactorX());
            double h = Math.abs(ctm.getScalingFactorY());
            double x = ctm.getTranslateX();
            double y = ctm.getTranslateY();
            double ix = Math.max(0, Math.min(x + w, pageWidth) - Math.max(x, 0));
            double iy = Math.max(0, Math.min(y + h, pageHeight) - Math.max(y, 0));
            double area = ix * iy;
            stats.totalArea += area;
            double ratio = pageArea > 0 ? area / pageArea : 0;
            if (ratio > stats.maxRatio) {
                stats.maxRatio = ratio;
            }
        }
    }
}
