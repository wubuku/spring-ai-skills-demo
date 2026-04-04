package com.example.demo.model;

public record MultimodalToken(String type, String content) {
    public static MultimodalToken vision(String content) {
        return new MultimodalToken("vision", content);
    }

    public static MultimodalToken content(String content) {
        return new MultimodalToken("content", content);
    }
}