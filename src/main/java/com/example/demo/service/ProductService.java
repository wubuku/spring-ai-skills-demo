package com.example.demo.service;

import com.example.demo.model.Product;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class ProductService {
    
    private final Map<Long, Product> products = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public ProductService() {
        // 初始化示例数据
        addProduct(new Product(null, "iPhone 15", "手机", 5999.0, "苹果最新旗舰手机", 50));
        addProduct(new Product(null, "华为 MatePad Pro", "平板", 3299.0, "高性能安卓平板", 30));
        addProduct(new Product(null, "Sony WH-1000XM5", "耳机", 2499.0, "降噪蓝牙耳机", 80));
        addProduct(new Product(null, "小米电视 65寸", "电视", 2999.0, "4K智能电视", 20));
        addProduct(new Product(null, "MacBook Air M3", "笔记本", 8999.0, "轻薄笔记本电脑", 15));
    }

    private void addProduct(Product product) {
        product.setId(idGenerator.getAndIncrement());
        products.put(product.getId(), product);
    }

    public List<Product> searchProducts(String keyword, String category, Double priceMin, Double priceMax) {
        return products.values().stream()
            .filter(p -> keyword == null || keyword.isBlank() ||
                p.getName().contains(keyword) || 
                p.getDescription().contains(keyword))
            .filter(p -> category == null || category.isBlank() || p.getCategory().equals(category))
            .filter(p -> priceMin == null || p.getPrice() >= priceMin)
            .filter(p -> priceMax == null || p.getPrice() <= priceMax)
            .sorted(Comparator.comparing(Product::getId))
            .collect(Collectors.toList());
    }

    public Optional<Product> getProductById(Long id) {
        return Optional.ofNullable(products.get(id));
    }

    // ========== 基于用户名的操作（用于认证透传）==========
    // 使用 username 作为 key 而不是 userId，这样可以从 Token 中获取用户身份

    private final Map<String, List<Long>> userCarts = new ConcurrentHashMap<>();

    public Map<String, Object> addToCartByUsername(String username, Long productId) {
        username = requireUsername(username);
        if (!products.containsKey(productId)) {
            return Map.of("success", false, "message", "商品不存在");
        }
        String resolvedUsername = username;
        List<Long> cart = userCarts.compute(resolvedUsername, (key, existing) ->
            append(existing, productId));
        return Map.of(
            "success", true,
            "message", "已添加到购物车",
            "cartSize", cart.size(),
            "username", resolvedUsername
        );
    }

    public Map<String, Object> checkoutByUsername(String username) {
        username = requireUsername(username);
        List<Long> cart = userCarts.remove(username);
        if (cart == null) {
            cart = List.of();
        }
        if (cart.isEmpty()) {
            return Map.of("success", false, "message", "购物车为空");
        }
        double total = cart.stream()
            .map(products::get)
            .filter(Objects::nonNull)
            .mapToDouble(Product::getPrice)
            .sum();
        return Map.of(
            "success", true,
            "message", "订单已提交",
            "totalAmount", total,
            "itemCount", cart.size(),
            "username", username
        );
    }

    public Map<String, Object> getCartByUsername(String username) {
        username = requireUsername(username);
        List<Long> cart = userCarts.getOrDefault(username, List.of());
        if (cart.isEmpty()) {
            return Map.of(
                "success", true,
                "message", "购物车为空",
                "items", List.of(),
                "totalAmount", 0.0,
                "itemCount", 0
            );
        }
        List<Product> products = cart.stream()
            .map(this.products::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        double total = products.stream().mapToDouble(Product::getPrice).sum();
        return Map.of(
            "success", true,
            "items", products,
            "totalAmount", total,
            "itemCount", cart.size()
        );
    }

    private List<Long> append(List<Long> existing, Long productId) {
        List<Long> updated = new ArrayList<>(existing == null ? List.of() : existing);
        updated.add(productId);
        return List.copyOf(updated);
    }

    private String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        return username;
    }
}
