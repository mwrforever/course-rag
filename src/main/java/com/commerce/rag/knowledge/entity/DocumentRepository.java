package com.commerce.rag.knowledge.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByKbId(UUID kbId);

    List<Document> findByKbIdAndParseStatus(UUID kbId, String parseStatus);
}
