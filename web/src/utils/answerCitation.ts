/**
 * answer 引用标记分段工具（Phase E，§8.3 硬性规则）
 *
 * - 仅做"标记 → citationId 字符串锚点"的显示映射，禁止在此自建来源对象
 *   （来源数据只能取自 response.citations / 回退 references，避免与后端
 *   AnswerCitationParser 的映射规则漂移）
 * - 未匹配标记由渲染层按纯文本处理
 */

/** 文本/引用分段 */
export type AnswerSegment =
    | { type: 'text'; text: string }
    | { type: 'citation'; citationId: string }

/** 引用标记正则：[S1] / [D2]（结构化 S、文档 D） */
const CITATION_PATTERN = /\[(S|D)(\d+)\]/g

/** 按 [S{n}] / [D{n}] 标记分段（标记段输出 citationId，如 "S1"） */
export function parseAnswerSegments(answer: string): AnswerSegment[] {
    if (!answer) {
        return []
    }
    const segments: AnswerSegment[] = []
    let lastIndex = 0
    for (const match of answer.matchAll(CITATION_PATTERN)) {
        const index = match.index ?? 0
        // 标记前的纯文本段
        if (index > lastIndex) {
            segments.push({type: 'text', text: answer.slice(lastIndex, index)})
        }
        segments.push({type: 'citation', citationId: `${match[1]}${match[2]}`})
        lastIndex = index + match[0].length
    }
    // 末尾剩余纯文本段
    if (lastIndex < answer.length) {
        segments.push({type: 'text', text: answer.slice(lastIndex)})
    }
    return segments
}
