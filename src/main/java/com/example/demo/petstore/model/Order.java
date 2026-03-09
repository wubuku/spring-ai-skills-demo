package com.example.demo.petstore.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private Long id;
    private Long petId;
    private Integer quantity;
    private OffsetDateTime shipDate;
    /** Order status: placed, approved, delivered */
    private String status;
    private Boolean complete;
}
