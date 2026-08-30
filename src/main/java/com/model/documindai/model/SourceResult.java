package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SourceResult {

    private String documentId;
    private String chunkId;
    private String chunkNumber;
}