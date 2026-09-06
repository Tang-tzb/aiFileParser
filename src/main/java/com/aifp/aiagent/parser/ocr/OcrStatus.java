package com.aifp.aiagent.parser.ocr;

/**
 * OCR 识别状态三态。
 * <p>
 * 引擎层（{@link TesseractOcrParser}）以状态化结果取代异常抛出：
 * 所有进程异常、超时、中断均在引擎层收敛为 {@link #FAILED}（附 errorMessage），
 * 上层只需面向本枚举三分支处理，不感知底层异常类型。
 *
 * @author Tang_tzb
 */
public enum OcrStatus {

    /**
     * 识别到有效词
     */
    SUCCESS,

    /**
     * 流程正常但 0 词（空白扫描页为正常业务态，不视为失败）
     */
    EMPTY,

    /**
     * 识别失败（引擎缺失/路径未配置、进程启动失败、超时、被中断、
     * 退出码非 0、输出不可解析等），errorMessage 携带具体原因
     */
    FAILED
}
