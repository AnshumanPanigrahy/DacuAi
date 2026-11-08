package com.model.documindai.model;

import lombok.Getter;

import java.util.List;
import java.util.TreeSet;

@Getter
public class SourceResult {

    private String documentId;
    private String chunkId;
    private String chunkNumber;
    private List<Integer> pageNumbers;

    public SourceResult(
            String documentId,
            String chunkId,
            String chunkNumber) {
        this(documentId, chunkId, chunkNumber, List.of());
    }

    public SourceResult(
            String documentId,
            String chunkId,
            String chunkNumber,
            List<Integer> pageNumbers) {
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.chunkNumber = chunkNumber;
        this.pageNumbers = pageNumbers == null
                ? List.of()
                : List.copyOf(new TreeSet<>(pageNumbers));
    }
}