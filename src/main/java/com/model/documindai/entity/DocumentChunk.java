package com.model.documindai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_number")
    private int chunkNumber;

    @Column(columnDefinition = "TEXT")
    private String chunkText;

    @ManyToOne
    @JoinColumn(name = "document_id")
    private Document document;


    public DocumentChunk() {
    }
    public DocumentChunk(int chunkNumber, String chunkText, Document document) {
        this.chunkNumber = chunkNumber;
        this.chunkText = chunkText;
        this.document = document;
    }
}