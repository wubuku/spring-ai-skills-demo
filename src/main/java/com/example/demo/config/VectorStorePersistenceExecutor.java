package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * VectorStore 持久化执行器
 * 在应用关闭时自动保存向量数据到文件
 */
public class VectorStorePersistenceExecutor {

    private static final Logger log = LoggerFactory.getLogger(VectorStorePersistenceExecutor.class);

    private final VectorStore vectorStore;
    private final String persistenceFile;

    public VectorStorePersistenceExecutor(VectorStore vectorStore, String persistenceFile) {
        this.vectorStore = vectorStore;
        this.persistenceFile = persistenceFile;
    }

    @PreDestroy
    public void saveVectorStore() {
        if (vectorStore instanceof SimpleVectorStore) {
            try {
                File file = new File(persistenceFile);
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                ((SimpleVectorStore) vectorStore).save(file);
                log.info("VectorStore 已保存到: {}", persistenceFile);
            } catch (Exception e) {
                log.error("保存 VectorStore 失败: {}", e.getMessage());
            }
        }
    }
}
