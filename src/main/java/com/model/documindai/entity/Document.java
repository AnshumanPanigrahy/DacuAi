package com.model.documindai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;
import jakarta.persistence.OneToMany;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "upload_time")
    private LocalDateTime uploadTime;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL)
    private List<DocumentChunk> chunks;

    public Document() {
    }
    public Document(String title, String fileName, LocalDateTime uploadTime) {
        this.title = title;
        this.fileName = fileName;
        this.uploadTime = uploadTime;
    }

}