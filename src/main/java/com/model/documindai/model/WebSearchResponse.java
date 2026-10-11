package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class WebSearchResponse {

    private String query;

    private List<WebSearchResult> results;
}