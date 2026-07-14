package com.model.documindai.service;

import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    public double[] generateEmbedding(String text) {

        double[] vector = new double[5];

        vector[0] = text.length();

        vector[1] = text.split("\\s+").length;

        vector[2] = text.chars().filter(Character::isUpperCase).count();

        vector[3] = text.chars().filter(Character::isDigit).count();

        vector[4] = text.chars().filter(Character::isLetter).count();

        return vector;
    }
}