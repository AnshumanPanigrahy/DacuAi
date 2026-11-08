package com.model.documindai.model;

import lombok.Getter;

import java.util.List;
import java.util.TreeSet;

@Getter
public class SearchResult {

    private double score;
    private String documentId;
    private String chunkId;
    private String chunkNumber;
    private String text;
    private String retrievalType;
    private List<Integer> pageNumbers;

    public SearchResult(
            double score,
            String documentId,
            String chunkId,
            String chunkNumber,
            String text,
            String retrievalType) {
        this(
                score,
                documentId,
                chunkId,
                chunkNumber,
                text,
                retrievalType,
                List.of()
        );
    }

    public SearchResult(
            double score,
            String documentId,
            String chunkId,
            String chunkNumber,
            String text,
            String retrievalType,
            List<Integer> pageNumbers) {
        this.score = score;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.chunkNumber = chunkNumber;
        this.text = text;
        this.retrievalType = retrievalType;
        this.pageNumbers = pageNumbers == null
                ? List.of()
                : List.copyOf(new TreeSet<>(pageNumbers));
    }
}