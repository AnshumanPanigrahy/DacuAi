package com.model.documindai.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

@Service
public class OcrService {

    private final Tesseract tesseract;

    public OcrService() {

        tesseract = new Tesseract();

        tesseract.setDatapath(
                "C:\\Program Files\\Tesseract-OCR\\tessdata"
        );

        tesseract.setLanguage("eng");
    }

    public String extractText(BufferedImage image) {

        try {
            return tesseract.doOCR(image);

        } catch (TesseractException e) {

            throw new RuntimeException(
                    "OCR processing failed",
                    e
            );
        }
    }
}