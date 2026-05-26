create extension if not exists vector;

create table if not exists ai_knowledge_chunk (
    id bigserial primary key,
    source_type varchar(32) not null,
    source_id bigint not null,
    chunk_no int not null default 0,
    title varchar(255) not null,
    content text not null,
    metadata jsonb not null default '{}'::jsonb,
    embedding vector(1536) not null,
    embedding_model varchar(64) not null,
    content_hash char(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (source_type, source_id, chunk_no)
);

create index if not exists idx_ai_knowledge_source
on ai_knowledge_chunk (source_type, source_id);

create index if not exists idx_ai_knowledge_embedding_hnsw
on ai_knowledge_chunk using hnsw (embedding vector_cosine_ops)
with (m = 16, ef_construction = 64);
