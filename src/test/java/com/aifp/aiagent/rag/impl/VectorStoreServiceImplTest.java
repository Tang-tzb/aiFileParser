package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@link VectorStoreServiceImpl} 测试
 * <p>
 * 离线单测：阶段 13 空切片防御（null/空列表不触达 Milvus）、非空正常入库、
 * 入库失败转 4001；search 的 query/topK/filterExpression 透传与失败转 4002。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreServiceImplTest {

    @Mock
    private VectorStore vectorStore;

    private VectorStoreServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VectorStoreServiceImpl(vectorStore);
    }

    // ==================== store：空切片防御 ====================

    /**
     * 阶段 13：null 切片列表直接跳过，不触达 vectorStore.add（Milvus 空插入行为不确定）。
     */
    @Test
    void store_nullChunks_skipsAdd() {
        service.store(null);

        verify(vectorStore, never()).add(anyList());
    }

    /**
     * 阶段 13：空切片列表直接跳过（空解析文档 0 块入库防御）。
     */
    @Test
    void store_emptyChunks_skipsAdd() {
        service.store(List.of());

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void store_nonEmptyChunks_addsAll() {
        List<Document> chunks = List.of(new Document("chunk-1"), new Document("chunk-2"));

        service.store(chunks);

        verify(vectorStore).add(chunks);
    }

    /**
     * 入库失败转 BusinessException(4001)，不向上层泄漏底层异常。
     */
    @Test
    void store_addFails_throws4001() {
        doThrow(new RuntimeException("milvus down")).when(vectorStore).add(anyList());

        assertThatThrownBy(() -> service.store(List.of(new Document("chunk-1"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4001));
    }

    // ==================== search：透传与失败转换 ====================

    @Test
    void search_withoutFilter_requestCarriesQueryAndTopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("hit-1")));

        List<Document> hits = service.search("项目名称", 5);

        assertThat(hits).hasSize(1);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("项目名称");
        assertThat(captor.getValue().getTopK()).isEqualTo(5);
        assertThat(captor.getValue().getFilterExpression()).isNull();
    }

    /**
     * filter 表达式透传（fileId 过滤防跨文件污染的底层契约）。
     */
    @Test
    void search_withFilter_propagatesFilterExpression() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search("项目名称", 5, "fileId == '1785800001'");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
        assertThat(captor.getValue().getFilterExpression().toString()).contains("fileId");
    }

    /**
     * 空白 filter 视为不过滤（与 null 同语义）。
     */
    @Test
    void search_blankFilter_treatedAsNoFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search("项目名称", 5, "   ");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNull();
    }

    /**
     * 检索失败转 BusinessException(4002)，不向上层泄漏底层异常。
     */
    @Test
    void search_failure_throws4002() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("milvus down"));

        assertThatThrownBy(() -> service.search("项目名称", 5, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4002));
    }
}
