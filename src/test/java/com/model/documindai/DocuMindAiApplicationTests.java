package com.model.documindai;

import com.model.documindai.service.GeminiModelService;
import com.model.documindai.service.GeminiVisionOcrService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class DocuMindAiApplicationTests {

    @MockitoBean
    private GeminiModelService geminiModelService;

    @MockitoBean
    private GeminiVisionOcrService geminiVisionOcrService;

    @Test
    void contextLoads() {
    }

}
