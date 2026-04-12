package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话历史查询服务
 *
 * 直接查询 SPRING_AI_CHAT_MEMORY 表获取会话消息信息，
 * 用于判断是否有会话历史，从而决定使用哪种图片处理流程。
 */
@Service
public class ConversationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);
    private final JdbcTemplate jdbcTemplate;
    private static final String TABLE_NAME = "SPRING_AI_CHAT_MEMORY";

    public ConversationHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 检查指定会话是否有历史消息
     */
    public boolean hasHistory(String conversationId) {
        String sql = String.format(
            "SELECT COUNT(*) FROM %s WHERE conversation_id = ?",
            TABLE_NAME);
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, conversationId);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查会话历史失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取消息数量
     */
    public int getMessageCount(String conversationId) {
        String sql = String.format(
            "SELECT COUNT(*) FROM %s WHERE conversation_id = ?",
            TABLE_NAME);
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, conversationId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("获取消息数量失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 获取最近 N 条消息的摘要（用于提示词增强）
     * 返回格式："| role | content |" 的 Markdown 表格
     */
    public String getRecentHistorySummary(String conversationId, int messageCount) {
        String sql = String.format("""
            SELECT type, content FROM %s
            WHERE conversation_id = ?
            ORDER BY "timestamp" DESC
            LIMIT ?
            """, TABLE_NAME);

        try {
            List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                sql, conversationId, messageCount);

            if (messages.isEmpty()) {
                return "（无历史消息）";
            }

            // 倒序排列（按时间正序）
            Collections.reverse(messages);

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                String role = msg.get("type").toString();
                String content = msg.get("content").toString();
                // 截断过长的内容
                if (content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                sb.append(String.format("| %s | %s |\n", role, content));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("获取会话历史摘要失败: {}", e.getMessage());
            return "（无历史消息）";
        }
    }
}