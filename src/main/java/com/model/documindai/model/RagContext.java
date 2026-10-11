package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class RagContext {

    private List<SearchResult> pdfResults;

    private List<WebSearchResult> webResults;
}