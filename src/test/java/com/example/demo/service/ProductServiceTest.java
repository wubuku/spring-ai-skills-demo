package com.example.demo.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ProductServiceTest {

    @Test
    void keepsCartsIsolatedByUsernameAndChecksProductExistence() {
        ProductService service = new ProductService();

        assertThat(service.addToCartByUsername("user1", 3L))
            .containsEntry("success", true);
        assertThat(service.getCartByUsername("user1"))
            .containsEntry("itemCount", 1);
        assertThat(service.getCartByUsername("user2"))
            .containsEntry("itemCount", 0);
        assertThat(service.addToCartByUsername("user1", 999L))
            .containsEntry("success", false);
    }

    @Test
    void concurrentAddsAreNotLostAndCheckoutAtomicallyRemovesTheCart() throws Exception {
        ProductService service = new ProductService();
        int additions = 100;
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < additions; i++) {
                tasks.add(() -> service.addToCartByUsername("concurrent-user", 3L));
            }
            pool.invokeAll(tasks);
        } finally {
            pool.shutdownNow();
        }

        assertThat(service.getCartByUsername("concurrent-user"))
            .containsEntry("itemCount", additions);
        assertThat(service.checkoutByUsername("concurrent-user"))
            .containsEntry("success", true)
            .containsEntry("itemCount", additions);
        assertThat(service.getCartByUsername("concurrent-user"))
            .containsEntry("itemCount", 0);
    }
}
