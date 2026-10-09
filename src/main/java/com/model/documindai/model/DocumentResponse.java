package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class DocumentResponse {

    private String id;
    private String fileName;
    private LocalDateTime uploadTime;
    private long chunkCount;
}
