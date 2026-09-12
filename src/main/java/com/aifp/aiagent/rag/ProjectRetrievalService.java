package com.aifp.aiagent.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 项目范围文档检索服务（需求 §五 层级 2/3、§二十八 ProjectRetrievalService）
 * <p>
 * 过滤语义（Phase 6 确认，不可变约束 1）：
 * 单项目 {@code (projectId == 'X' || fileId in ['...'])}，
 * 跨项目 {@code (projectId in ['X','Y'] || fileId in ['...'])}。
 * projectId 直查 Phase 5 新 chunk；历史 chunk（先入库后绑定项目，无 projectId metadata）
 * 经 fileId in [项目当前关联文件] 兜底（约束 3：兜底仅依赖 file_record 实时查询，
 * 禁止扫描 Milvus 推断归属；约束 4：解绑后不得再被命中，不缓存文件ID）。
 * OR 为行级谓词，同时命中两子句的行只返回一次。
 * <p>
 * 职责边界（约束 13）：只解决"项目范围内找文档"；项目字段值判断、金额计算、
 * 项目比较、最终回答分别属 Phase 7 Structured Query 与 Phase 8 Project Assistant。
 * 抽取链路的 fileId 单文件隔离不经过本服务（约束 9：原 FileId Filter 独立存在）。
 *
 * @author Tang_tzb
 */
public interface ProjectRetrievalService {

    /**
     * 当前项目范围检索（§五 层级 2）。
     *
     * @param projectId 项目ID（不可空；先权限/存在性守门，再查项目文件组装兜底过滤）
     * @param query     查询文本（空白返回空列表，不调用向量库）
     * @param topK      返回条数（原样透传向量库，不做分页/截断/重排）
     * @return 命中切片（原样返回，不改写 metadata，供 Phase 11 溯源）
     */
    List<Document> retrieve(Long projectId, String query, int topK);

    /**
     * 跨项目范围检索（§五 层级 3，Phase 10 比较/聚合复用）。
     * <p>
     * 逐项目权限/存在性守门全部通过后才查询项目文件（约束 2：权限先于任何文件查询）；
     * fileId 兜底并集构造前 distinct + sort，保证 Filter 稳定可测试（约束 5）。
     *
     * @param projectIds 项目ID列表（null/空/含 null 抛 IllegalArgumentException）
     * @param query      查询文本（空白返回空列表，不调用向量库）
     * @param topK       返回条数（原样透传向量库）
     * @return 命中切片（原样返回）
     */
    List<Document> retrieve(List<Long> projectIds, String query, int topK);

    /**
     * 项目内指定文件范围检索（§二十 searchProjectFiles，Phase 7）。
     * <p>
     * 安全边界（约束 6）：{@code fileIds ∩ 项目当前关联文件} 取交集后构造 fileId IN 子句，
     * 交集外 ID 一律不进入检索条件——不访问项目外文件；不缓存文件归属，
     * 文件解除项目关联后立即失去本项目检索资格；交集为空直接返回空列表，
     * <b>绝不调用向量库</b>。
     *
     * @param projectId 项目ID（不可空；先权限/存在性守门）
     * @param query     查询文本（空白返回空列表，不调用向量库）
     * @param fileIds   指定文件ID列表（null/empty/含 null 抛 IllegalArgumentException）
     * @param topK      返回条数（原样透传向量库）
     * @return 命中切片（原样返回，不改写 metadata，供 Phase 11 溯源）
     */
    List<Document> searchProjectFiles(Long projectId, String query, List<Long> fileIds, int topK);
}
