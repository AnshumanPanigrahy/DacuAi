package com.model.documindai.service;

import com.model.documindai.model.WebSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebContextBuilder {

    public String build(List<WebSearchResult> results) {

        if (results == null || results.isEmpty()) {
            return "No reliable web sources were found.";
        }

        StringBuilder context = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {

            WebSearchResult result = results.get(i);

            context.append("WEB SOURCE ")
                    .append(i + 1)
                    .append("\n");

            context.append("Title: ")
                    .append(result.getTitle())
                    .append("\n");

            context.append("URL: ")
                    .append(result.getUrl())
                    .append("\n");

            context.append("Search relevance score: ")
                    .append(result.getScore())
                    .append("\n");

            context.append("Content:\n")
                    .append(result.getContent())
                    .append("\n");

            context.append("--------------------------------\n\n");
        }

        return context.toString();
    }
}