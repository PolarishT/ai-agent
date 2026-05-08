package com.involutionhell.backend.rag.retrieval.persistence;

public interface RagUserRepository {

    void upsertSeen(String userId);
}
