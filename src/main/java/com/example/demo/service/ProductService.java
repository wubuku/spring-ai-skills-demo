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
    private final Map<Long, List<Long>> carts = new ConcurrentHashMap<>();

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
            .filter(p -> keyword == null || 
                p.getName().contains(keyword) || 
                p.getDescription().contains(keyword))
            .filter(p -> category == null || p.getCategory().equals(category))
            .filter(p -> priceMin == null || p.getPrice() >= priceMin)
            .filter(p -> priceMax == null || p.getPrice() <= priceMax)
            .collect(Collectors.toList());
    }

    public Optional<Product> getProductById(Long id) {
        return Optional.ofNullable(products.get(id));
    }

    public Map<String, Object> addToCart(Long userId, Long productId) {
        if (!products.containsKey(productId)) {
            return Map.of("success", false, "message", "商品不存在");
        }
        carts.computeIfAbsent(userId, k -> new ArrayList<>()).add(productId);
        return Map.of(
            "success", true, 
            "message", "已添加到购物车",
            "cartSize", carts.get(userId).size()
        );
    }

    public Map<String, Object> checkout(Long userId) {
        List<Long> cart = carts.getOrDefault(userId, new ArrayList<>());
        if (cart.isEmpty()) {
            return Map.of("success", false, "message", "购物车为空");
        }
        double total = cart.stream()
            .map(products::get)
            .filter(Objects::nonNull)
            .mapToDouble(Product::getPrice)
            .sum();
        carts.remove(userId);
        return Map.of(
            "success", true,
            "message", "订单已提交",
            "totalAmount", total,
            "itemCount", cart.size()
        );
    }
}
