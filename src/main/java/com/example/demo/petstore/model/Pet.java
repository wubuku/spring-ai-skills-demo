package com.example.demo.petstore.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Pet {
    private Long id;
    private String name;
    private Category category;
    private List<String> photoUrls;
    private List<Tag> tags;
    /** pet status in the store: available, pending, sold */
    private String status;
}
