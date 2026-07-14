package com.model.documindai.model;

import lombok.Getter;

@Getter
public class VectorDocument {

    private String text;
    private double[] vector;

    public VectorDocument(String text, double[] vector) {
        this.text = text;
        this.vector = vector;
    }

}