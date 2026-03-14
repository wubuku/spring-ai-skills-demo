---
name: add-to-cart
description: 将指定商品加入用户购物车
version: 2.1
links:
  - name: checkout
    description: 结算购物车中的商品
---

# 加入购物车技能

## 功能描述
将指定商品加入当前登录用户的购物车。**用户身份自动从认证信息中获取，无需传入 userId 参数！**

## ⚠️ 重要：不需要 userId 参数！
- 此 API **不需要** userId 参数
- 用户身份由后端从请求头的认证信息（Authorization）自动获取
- 只需要传入 productId 即可

## API 端点
```
POST /api/products/cart
```

## 请求参数（Query）
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| productId | integer | 是 | 商品 ID |

## 调用示例
```
POST /api/products/cart?productId=3
```

## 返回结构
```json
{
  "success": true,
  "message": "已添加到购物车",
  "cartSize": 2,
  "username": "user1"
}
```

## 下一步建议
商品加入购物车后，可以：
- 继续购物（返回 search-products）
- 使用 `checkout` 结算订单
