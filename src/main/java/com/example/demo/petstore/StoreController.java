package com.example.demo.petstore;

import com.example.demo.petstore.model.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v3/store")
public class StoreController {
    private final PetStoreService service;
    public StoreController(PetStoreService service) { this.service = service; }

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Long>> getInventory() { return ResponseEntity.ok(service.getInventory()); }

    @PostMapping("/order")
    public ResponseEntity<Order> placeOrder(@RequestBody Order order) { return ResponseEntity.ok(service.placeOrder(order)); }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long orderId) {
        return service.getOrderById(orderId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/order/{orderId}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long orderId) {
        return service.deleteOrder(orderId) ? ResponseEntity.ok().<Void>build() : ResponseEntity.notFound().<Void>build();
    }
}