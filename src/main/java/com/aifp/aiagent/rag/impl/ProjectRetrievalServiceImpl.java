package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.ProjectRetrievalService;
import com.aifp.aiagent.rag.VectorStoreService;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 项目范围文档检索服务实现（Phase 6）
 * <p>
 * 执行序（约束 2）：参数防御 → 逐项目权限/存在性守门 → 实时查询项目文件组装兜底过滤
 * → 单次向量检索。守门全部通过前不发起任何文件查询，避免泄露项目资源信息。
 * 日志只记 projectIds/topK/命中数（约束 12：不打印用户问题正文与完整文件ID列表）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRetrievalServiceImpl implements ProjectRetrievalService {

    private final VectorStoreService vectorStoreService;
    private final FileService fileService;
    private final ProjectAccessService projectAccessService;
    private final ProjectMapper projectMapper;

    @Override
    public List<Document> retrieve(Long projectId, String query, int topK) {
        Objects.requireNonNull(projectId, "projectId 不可为空");
        if (isBlank(query)) {
            return List.of();
        }
        ensureProjectGuard(projectId);
        // 兜底 fileId 实时查询当前 file_record.project_id（约束 4：解绑后不得再命中；不缓存）
        List<Long> fallbackFileIds = fileService.listFileIdsByProject(projectId);
        String filter = buildFilter(List.of(projectId), fallbackFileIds);
        List<Document> hits = vectorStoreService.search(query, topK, filter);
        log.info("项目范围检索完成 projectId={}, topK={}, filterLength={}, hits={}",
                projectId, topK, filter.length(), hits.size());
        return hits;
    }

    @Override
    public List<Document> retrieve(List<Long> projectIds, String query, int topK) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new IllegalArgumentException("projectIds 不可为空");
        }
        // null 元素在构造 Filter 前拒绝，禁止生成 projectId == 'null'（约束 6）
        if (projectIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("projectIds 不允许包含 null 元素");
        }
        if (isBlank(query)) {
            return List.of();
        }
        // distinct + sort：projectId 子句确定性（约束 5 的 Filter 稳定可测试语义）
        List<Long> distinctIds = projectIds.stream().distinct().sorted().toList();
        // 权限先于任何文件查询：守门全部通过后才查 file_record（约束 2）
        distinctIds.forEach(this::ensureProjectGuard);
        List<Long> fallbackFileIds = distinctIds.stream()
                .flatMap(id -> fileService.listFileIdsByProject(id).stream())
                .distinct()
                .sorted()
                .toList();
        String filter = buildFilter(distinctIds, fallbackFileIds);
        List<Document> hits = vectorStoreService.search(query, topK, filter);
        log.info("跨项目范围检索完成 projectIds={}, topK={}, filterLength={}, hits={}",
                distinctIds, topK, filter.length(), hits.size());
        return hits;
    }

    // ==================== 内部方法 ====================

    /**
     * 组装层级过滤表达式（约束 1 语义固定，约束 10：独立方法便于未来替换）：
     * projectId 直查子句在前、fileId 兜底子句在后，OR 连接并括号分组；
     * fileId 构造前 distinct + sort 保证表达式确定性（约束 5）；
     * 无兜底文件时省略 OR 子句（空 IN 列表非法，非"优化删除兜底"）。
     * <p>
     * 值恒为 Long 数字串（Phase 5 metadata String 化约定），无引号注入面。
     */
    private String buildFilter(List<Long> projectIds, List<Long> fallbackFileIds) {
        String projectClause = projectIds.size() == 1
                ? "projectId == '" + projectIds.get(0) + "'"
                : "projectId in [" + quoted(projectIds) + "]";
        if (fallbackFileIds == null || fallbackFileIds.isEmpty()) {
            return projectClause;
        }
        List<Long> distinctSorted = fallbackFileIds.stream().distinct().sorted().toList();
        return "(" + projectClause + " || fileId in [" + quoted(distinctSorted) + "])";
    }

    /**
     * Long 列表 → 单引号字符串列表（"1,2,3" 形态，供 IN 子句拼装）。
     */
    private String quoted(List<Long> ids) {
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    }

    /**
     * 项目访问权限 + 存在性守门（权限判断唯一入口，镜像 Phase 4 ensureProjectGuard）：
     * 无权限 FORBIDDEN(403)，项目不存在 PROJECT_NOT_FOUND(6001)。
     */
    private void ensureProjectGuard(Long projectId) {
        if (!projectAccessService.canAccess(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
    }

    private boolean isBlank(String query) {
        return query == null || query.isBlank();
    }
}
