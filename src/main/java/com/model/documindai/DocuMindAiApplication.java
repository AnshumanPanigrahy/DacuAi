package com.model.documindai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocuMindAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocuMindAiApplication.class, args);
    }

}
