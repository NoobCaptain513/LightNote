package com.lightnote.ai.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 操作 PostgreSQL/pgvector 里的 ai_knowledge_chunk 表
 */
@Repository
public class PgVectorKnowledgeRepository {
    private static final String TABLE_NAME = "ai_knowledge_chunk";

    private final JdbcTemplate jdbcTemplate;

    public PgVectorKnowledgeRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取 pgvector 库中指定 embeddingModel 的数量
     *
     * @param embeddingModel
     * @return
     */
    public long count(String embeddingModel) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from " + TABLE_NAME + " where embedding_model = ?",
                    Long.class,
                    embeddingModel);
            return count == null ? 0L : count;
        } catch (Exception e) {
            throw new IllegalStateException("查询 pgvector 知识库数量失败", e);
        }
    }

    /**
     * 清空 pgvector 库
     *
     * @return
     */
    public void deleteAll() {
        try {
            jdbcTemplate.update("delete from " + TABLE_NAME);
        } catch (Exception e) {
            throw new IllegalStateException("清空 pgvector 知识库失败", e);
        }
    }

    /**
     * 删除 pgvector 库中指定 sourceType 的旧知识
     *
     * @param sourceType
     * @param embeddingModel
     * @param sourceIds
     */
    public void deleteStaleSource(String sourceType, String embeddingModel, List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            String sql = "delete from " + TABLE_NAME + " where source_type = ? and embedding_model = ?";
            try {
                jdbcTemplate.update(sql, sourceType, embeddingModel);
                return;
            } catch (Exception e) {
                throw new IllegalStateException("清理 pgvector 旧知识失败", e);
            }
        }

        String sql = "delete from " + TABLE_NAME + " "
                + "where source_type = ? and embedding_model = ? and not (source_id = any (?))";
        try {
            jdbcTemplate.update(sql, statement -> {
                statement.setString(1, sourceType);
                statement.setString(2, embeddingModel);
                statement.setArray(3, statement.getConnection().createArrayOf("bigint", sourceIds.toArray(new Long[0])));
            });
        } catch (Exception e) {
            throw new IllegalStateException("清理 pgvector 旧知识失败", e);
        }
    }

    /**
     * 批量写入 pgvector 库
     *
     * @param chunks
     */
    public void upsertAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String sql = "insert into " + TABLE_NAME + " "
                + "(source_type, source_id, chunk_no, title, content, metadata, embedding, embedding_model, content_hash) "
                + "values (?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?) "
                + "on conflict (source_type, source_id, chunk_no) do update set "
                + "title = excluded.title, "
                + "content = excluded.content, "
                + "metadata = excluded.metadata, "
                + "embedding = excluded.embedding, "
                + "embedding_model = excluded.embedding_model, "
                + "content_hash = excluded.content_hash, "
                + "updated_at = now()";

        try {
            jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), (statement, chunk) -> {
                statement.setString(1, chunk.getSourceType());
                statement.setLong(2, chunk.getSourceId());
                statement.setInt(3, chunk.getChunkNo());
                statement.setString(4, chunk.getTitle());
                statement.setString(5, chunk.getContent());
                statement.setString(6, chunk.getMetadataJson() == null ? "{}" : chunk.getMetadataJson());
                statement.setString(7, toVectorLiteral(chunk.getEmbedding()));
                statement.setString(8, chunk.getEmbeddingModel());
                statement.setString(9, chunk.getContentHash());
            });
        } catch (Exception e) {
            throw new IllegalStateException("写入 pgvector 知识库失败", e);
        }
    }

    /**
     * 搜索 pgvector 库
     *
     * @param queryEmbedding
     * @param embeddingModel
     * @param limit
     * @return
     */
    public List<RagHit> search(float[] queryEmbedding, String embeddingModel, int limit) {
        String queryVector = toVectorLiteral(queryEmbedding);
        String sql = "select id, title, content, source_type, source_id, "
                + "1 - (embedding <=> ?::vector) as score "
                + "from " + TABLE_NAME + " "
                + "where embedding_model = ? "
                + "order by embedding <=> ?::vector "
                + "limit ?";

        try {
            return jdbcTemplate.query(
                    sql,
                    (resultSet, rowNum) -> new RagHit()
                            .setId(resultSet.getLong("id"))
                            .setTitle(resultSet.getString("title"))
                            .setContent(resultSet.getString("content"))
                            .setSourceType(resultSet.getString("source_type"))
                            .setSourceId(resultSet.getLong("source_id"))
                            .setScore(resultSet.getDouble("score")),
                    queryVector,
                    embeddingModel,
                    queryVector,
                    Math.max(1, limit));
        } catch (Exception e) {
            throw new IllegalStateException("搜索 pgvector 知识库失败", e);
        }
    }

    /**
     * 将向量转换为 PostgreSQL 的 vector 类型的文本表示
     *
     * @param vector
     * @return
     */
    private String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }

        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            float value = vector[i];
            builder.append(Float.isFinite(value) ? Float.toString(value) : "0");
        }
        return builder.append(']').toString();
    }
}
