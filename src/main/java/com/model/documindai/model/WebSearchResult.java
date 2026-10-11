package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class WebSearchResult {

    private String title;

    private String url;

    private String content;

    private double score;
}