package com.model.documindai.model;

import java.util.List;

public record PageAwareChunk(List<Integer> pageNumbers, String text) {
}
