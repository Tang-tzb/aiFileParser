package com.aifp.aiagent.parser.pdf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 页面渲染器：PDF 单页 → 临时 PNG（OCR 识别输入）。
 * <p>
 * 资源约束实现：
 * <ul>
 *   <li>单页渲染、即用即删（调用方在 finally 中删除临时文件）——
 *       满足"不一次性加载所有页面图片"；</li>
 *   <li>默认 200 DPI（识别质量与内存平衡点，禁默认超高分辨率）。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class PageImageRenderer {

    /**
     * 页面渲染 DPI（默认 200）
     */
    @Value("${document.parser.pdf.ocr.render-dpi:200}")
    private int renderDpi = 200;

    /**
     * 渲染指定页为临时 PNG（RGB）。
     *
     * @param document  已加载的 PDF 文档
     * @param pageIndex 页索引（0-based）
     * @return 渲染产物（临时文件 + 元数据）；临时文件由调用方负责删除
     * @throws IOException 渲染或写盘失败（沿用 FILE_PARSE_ERROR 语义，由调用方处理）
     */
    public RenderedPage renderToTempPng(PDDocument document, int pageIndex) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);

        File tempFile = Files.createTempFile("aifp-ocr-", ".png").toFile();
        ImageIO.write(image, "png", tempFile);
        log.debug("页面渲染完成 pageIndex={}, dpi={}, size={}x{}, file={}",
                pageIndex, renderDpi, image.getWidth(), image.getHeight(), tempFile.getName());
        return new RenderedPage(tempFile, image.getWidth(), image.getHeight(), renderDpi);
    }

    /**
     * 渲染产物元数据：临时文件 + 图像尺寸 + DPI（供 OcrRequest 填充，
     * 识别层无需二次解码图片）。
     */
    @Data
    @AllArgsConstructor
    public static class RenderedPage {
        private final File file;
        private final int width;
        private final int height;
        private final int dpi;
    }
}
