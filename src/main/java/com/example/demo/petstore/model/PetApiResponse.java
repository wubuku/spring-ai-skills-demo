package com.example.demo.petstore.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PetApiResponse {
    private Integer code;
    private String type;
    private String message;
}
