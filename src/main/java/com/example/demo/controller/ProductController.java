package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    @Operation(summary = "搜索商品", description = "根据关键词、分类、价格范围搜索商品（公开接口，无需认证）")
    public List<Product> searchProducts(
        @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
        @Parameter(description = "商品分类") @RequestParam(required = false) String category,
        @Parameter(description = "最低价格") @RequestParam(required = false) Double priceMin,
        @Parameter(description = "最高价格") @RequestParam(required = false) Double priceMax
    ) {
        return productService.searchProducts(keyword, category, priceMin, priceMax);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情", description = "根据商品 ID 获取详细信息（公开接口，无需认证）")
    public Product getProductDetail(@PathVariable Long id) {
        return productService.getProductById(id)
            .orElseThrow(() -> new RuntimeException("商品不存在"));
    }

    /**
     * 受保护的 API：加入购物车
     * 使用 Spring Security @PreAuthorize 注解保护 - 需要已认证用户
     * userId 从 Spring Security Authentication 或 X-Authenticated-User 头获取（用于 AI Agent 代用户调用场景）
     */
    @PostMapping("/cart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "加入购物车（受保护）", description = "将指定商品加入当前用户购物车，需要认证")
    public Map<String, Object> addToCart(
        @Parameter(description = "商品 ID") @RequestParam Long productId,
        @RequestHeader(value = "X-Authenticated-User", required = false) String authenticatedUser,
        Authentication authentication
    ) {
        // 优先从 Spring Security Authentication 获取，其次从请求头获取（用于 AI Agent 代调用场景）
        String username = authentication != null && authentication.isAuthenticated()
            ? authentication.getName()
            : authenticatedUser;
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
        @RequestHeader(value = "X-Authenticated-User", required = false) String authenticatedUser,
        Authentication authentication
    ) {
        String username = authentication != null && authentication.isAuthenticated()
            ? authentication.getName()
            : authenticatedUser;
        return productService.checkoutByUsername(username);
    }

    /**
     * 受保护的 API：查看购物车
     */
    @GetMapping("/cart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查看购物车（受保护）", description = "查看当前用户的购物车内容，需要认证")
    public Map<String, Object> getCart(
        @RequestHeader(value = "X-Authenticated-User", required = false) String authenticatedUser,
        Authentication authentication
    ) {
        String username = authentication != null && authentication.isAuthenticated()
            ? authentication.getName()
            : authenticatedUser;
        return productService.getCartByUsername(username);
    }

    // ========== 保留旧接口用于兼容（已弃用）==========
    @Deprecated
    @PostMapping("/cart-legacy")
    @Operation(summary = "[已弃用] 加入购物车", description = "旧接口，需要前端传入 userId")
    public Map<String, Object> addToCartLegacy(
        @Parameter(description = "用户 ID（已弃用，请使用认证）") @RequestParam Long userId,
        @Parameter(description = "商品 ID") @RequestParam Long productId
    ) {
        return productService.addToCart(userId, productId);
    }

    @Deprecated
    @PostMapping("/checkout-legacy")
    @Operation(summary = "[已弃用] 结算订单", description = "旧接口，需要前端传入 userId")
    public Map<String, Object> checkoutLegacy(
        @Parameter(description = "用户 ID（已弃用，请使用认证）") @RequestParam Long userId
    ) {
        return productService.checkout(userId);
    }
}
