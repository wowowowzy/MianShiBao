package com.demo.interview.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语义检索通用编排：查询向量化 → rerank 精排下标。
 * 供常规出题与大厂出题共用，避免各自重复调用 embedding / rerank 客户端。
 */
@Slf4j
@Component
public class SemanticRanker {

    private final EmbeddingCacheService embeddingCache;
    private final RankClient rankClient;

    public SemanticRanker(EmbeddingCacheService embeddingCache, RankClient rankClient) {
        this.embeddingCache = embeddingCache;
        this.rankClient = rankClient;
    }

    public double[] embed(String query) throws Exception {
        return embeddingCache.embedCached(query);
    }

    /** 精排并返回对应候选下标（按相关度降序） */
    public List<Integer> rerank(String query, List<String> documents, int pick) throws Exception {
        return rankClient.rerank(query, documents, pick).stream().map(RankClient.Ranked::index).toList();
    }
}