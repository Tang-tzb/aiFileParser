package com.aifp.aiagent.parser.pdf.layout;

import java.util.List;

/**
 * 表格结构识别器接口（阶段 7，对应《PDF解析改造方案》§九）。
 * <p>
 * 三实现分工：
 * <ul>
 *   <li>{@link ImageTableRecognizer}：方式一，规则型表格线恢复器（有清晰边框的常规表单）；</li>
 *   <li>{@link CoordinateTableRecognizer}：方式二，文字坐标 X/Y 聚类兜底（无边框表格）；</li>
 *   <li>{@link HybridTableRecognizer}：编排器（Spring 装配入口），格网择优 + 值绑定 +
 *       表头候选/OCR + 表头-值融合。</li>
 * </ul>
 * 异常约定：实现必须全异常封闭——任何内部失败收敛为空列表返回（降级不中断解析）。
 *
 * @author Tang_tzb
 */
public interface TableStructureRecognizer {

    /**
     * 识别表格结构。
     *
     * @param input 识别输入（渲染图 + 文字块 + 区域）
     * @return 识别出的表格（无有效表格/失败降级返回空列表，不抛异常）
     */
    List<TableGrid> recognize(TableRecognitionInput input);
}
