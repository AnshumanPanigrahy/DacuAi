package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SearchResult {

    private double score;
    private String documentId;
    private String chunkId;
    private String chunkNumber;
    private String text;
    private String retrievalType;
}