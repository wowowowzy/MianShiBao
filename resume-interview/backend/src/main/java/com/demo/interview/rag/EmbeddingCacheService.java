package com.demo.interview.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量缓存服务：embedCached(text) 先查缓存，命中复用；未命中调 DashScope 计算并回写（只付一次钱）。
 * 缓存按真实查询文本（简历技能拼接串）懒加载，不做无效预埋。
 */
@Slf4j
@Service
public class EmbeddingCacheService {

    private final DashScopeEmbeddingClient client;
    private final EmbeddingCacheRepository repository;
    private final String model;
    private final ObjectMapper json;

    public EmbeddingCacheService(DashScopeEmbeddingClient client, EmbeddingCacheRepository repository,
                                 DashScopeProperties props, ObjectMapper json) {
        this.client = client;
        this.repository = repository;
        this.model = props.embeddingModel();
        this.json = json;
    }

    /** 取某段文本的向量：缓存命中直接复用，未命中计算并回写（调用方负责捕获降级） */
    public double[] embedCached(String text) throws Exception {
        String cached = repository.getVector(text);
        if (cached != null) {
            return toVector(cached);
        }
        double[] vec = client.embed(List.of(text)).get(0);
        repository.put(text, model, toJson(vec));
        log.info("embedding 缓存未命中，已计算并回写：{}", text);
        return vec;
    }

    private String toJson(double[] v) throws Exception {
        List<Double> list = new ArrayList<>(v.length);
        for (double x : v) {
            list.add(x);
        }
        return json.writeValueAsString(list);
    }

    private double[] toVector(String vectorText) throws Exception {
        List<Double> list = json.readValue(vectorText, new TypeReference<>() {
        });
        double[] d = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            d[i] = list.get(i);
        }
        return d;
    }
}