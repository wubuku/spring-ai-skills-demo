package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@Tag(name = "商品管理", description = "商品搜索、详情、购物车操作")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "搜索商品", description = "根据关键词、分类、价格范围搜索商品")
    public List<Product> searchProducts(
        @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
        @Parameter(description = "商品分类") @RequestParam(required = false) String category,
        @Parameter(description = "最低价格") @RequestParam(required = false) Double priceMin,
        @Parameter(description = "最高价格") @RequestParam(required = false) Double priceMax
    ) {
        return productService.searchProducts(keyword, category, priceMin, priceMax);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情", description = "根据商品 ID 获取详细信息")
    public Product getProductDetail(@PathVariable Long id) {
        return productService.getProductById(id)
            .orElseThrow(() -> new RuntimeException("商品不存在"));
    }

    @PostMapping("/cart")
    @Operation(summary = "加入购物车", description = "将指定商品加入用户购物车")
    public Map<String, Object> addToCart(
        @Parameter(description = "用户 ID") @RequestParam Long userId,
        @Parameter(description = "商品 ID") @RequestParam Long productId
    ) {
        return productService.addToCart(userId, productId);
    }

    @PostMapping("/checkout")
    @Operation(summary = "结算订单", description = "提交购物车中的商品并生成订单")
    public Map<String, Object> checkout(
        @Parameter(description = "用户 ID") @RequestParam Long userId
    ) {
        return productService.checkout(userId);
    }
}
