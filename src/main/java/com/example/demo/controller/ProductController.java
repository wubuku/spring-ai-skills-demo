package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@Tag(name = "商品管理", description = "商品搜索、详情、购物车操作")
@Validated
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "搜索商品", description = "根据关键词、分类、价格范围搜索商品（公开接口，无需认证）")
    public List<Product> searchProducts(
        @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
        @Parameter(description = "商品分类") @RequestParam(required = false) String category,
        @Parameter(description = "最低价格")
        @PositiveOrZero(message = "priceMin 不能为负数")
        @RequestParam(required = false) Double priceMin,
        @Parameter(description = "最高价格")
        @PositiveOrZero(message = "priceMax 不能为负数")
        @RequestParam(required = false) Double priceMax
    ) {
        if (priceMin != null && priceMax != null && priceMin > priceMax) {
            throw new IllegalArgumentException("priceMin 不能大于 priceMax");
        }
        return productService.searchProducts(keyword, category, priceMin, priceMax);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情", description = "根据商品 ID 获取详细信息（公开接口，无需认证）")
    public Product getProductDetail(@PathVariable Long id) {
        return productService.getProductById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "商品不存在"));
    }

    /**
     * 受保护的 API：加入购物车
     * 使用 Spring Security @PreAuthorize 注解保护 - 需要已认证用户
     * 用户身份只从 Spring Security Authentication 获取，不能由请求参数或自定义请求头覆盖。
     */
    @PostMapping("/cart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "加入购物车（受保护）", description = "将指定商品加入当前用户购物车，需要认证")
    public Map<String, Object> addToCart(
        @Parameter(description = "商品 ID")
        @PositiveOrZero(message = "productId 不能为负数")
        @RequestParam Long productId,
        Authentication authentication
    ) {
        String username = authenticatedUsername(authentication);
        return productService.addToCartByUsername(username, productId);
    }

    /**
     * 受保护的 API：结算订单
     * 使用 Spring Security @PreAuthorize 注解保护 - 需要已认证用户
     */
    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "结算订单（受保护）", description = "提交购物车中的商品并生成订单，需要认证")
    public Map<String, Object> checkout(
        Authentication authentication
    ) {
        String username = authenticatedUsername(authentication);
        return productService.checkoutByUsername(username);
    }

    /**
     * 受保护的 API：查看购物车
     */
    @GetMapping("/cart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查看购物车（受保护）", description = "查看当前用户的购物车内容，需要认证")
    public Map<String, Object> getCart(
        Authentication authentication
    ) {
        String username = authenticatedUsername(authentication);
        return productService.getCartByUsername(username);
    }

    private String authenticatedUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        return authentication.getName();
    }
}
