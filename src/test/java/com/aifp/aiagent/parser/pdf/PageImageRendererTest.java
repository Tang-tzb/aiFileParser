package com.aifp.aiagent.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link PageImageRenderer} 测试：单页渲染产物元数据 + 临时文件生命周期。
 *
 * @author Tang_tzb
 */
class PageImageRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void renderToTempPng_producesFileWithDpiScaledMetadata() throws Exception {
        File pdf = tempDir.resolve("sample.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            // 显式 A4 页面：595 × 842 pt（PDPage 默认为 LETTER）
            doc.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(595f, 842f)));
            doc.save(pdf);
        }

        PageImageRenderer renderer = new PageImageRenderer();
        PageImageRenderer.RenderedPage rendered;
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            rendered = renderer.renderToTempPng(doc, 0);
        }

        try {
            assertThat(rendered.getFile()).exists();
            assertThat(rendered.getDpi()).isEqualTo(200);
            // 595/72×200 = 1652.8、842/72×200 = 2338.9（±2px 容差）
            assertThat((double) rendered.getWidth()).isCloseTo(595 / 72.0 * 200, within(2.0));
            assertThat((double) rendered.getHeight()).isCloseTo(842 / 72.0 * 200, within(2.0));
        } finally {
            Files.deleteIfExists(rendered.getFile().toPath());
        }
        // 临时文件用后即删
        assertThat(rendered.getFile()).doesNotExist();
    }
}
