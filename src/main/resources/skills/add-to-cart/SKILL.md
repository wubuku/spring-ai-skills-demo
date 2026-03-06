---
name: add-to-cart
description: 将指定商品加入用户购物车
version: 1.0
links:
  - name: checkout
    description: 结算购物车中的商品
---

# 加入购物车技能

## 功能描述
将指定商品加入用户的购物车。

## API 端点
```
POST /api/products/cart
```

## 请求参数（Query）
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | integer | 是 | 用户 ID（默认使用 1） |
| productId | integer | 是 | 商品 ID |

## 调用示例
```
POST /api/products/cart?userId=1&productId=3
```

## 返回结构
```json
{
  "success": true,
  "message": "已添加到购物车",
  "cartSize": 2
}
```

## 下一步建议
商品加入购物车后，可以：
- 继续购物（返回 search-products）
- 使用 `checkout` 结算订单
