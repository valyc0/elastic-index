package io.bootify.my_app.repository;

import io.bootify.my_app.model.ParsedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParsedDocumentRepository extends JpaRepository<ParsedDocument, String> {

    List<ParsedDocument> findAllByOrderByCreatedAtDesc();

    List<ParsedDocument> findByState(String state);
}
