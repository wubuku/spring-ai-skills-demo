---
name: view-cart
description: 查看当前登录用户的购物车内容
version: 1.0
links:
  - name: checkout
    description: 结算购物车中的商品
---

# 查看购物车技能

## 功能描述
查看当前登录用户的购物车内容。**用户身份自动从认证信息中获取，无需传入 userId 参数！**

## 重要：不需要 userId 参数！
- 此 API **不需要** userId 参数
- 用户身份由后端从请求头的认证信息（Authorization）自动获取

## API 端点
```
GET /api/products/cart
```

## 请求参数
无请求参数。用户身份从 Authorization 请求头中自动获取。

## 调用示例
```
GET /api/products/cart
```

## 返回结构

购物车中的 `items` 是商品对象数组，字段与 `GET /api/products/{id}` 返回的
`Product` 模型一致；当前 Demo 使用列表中的重复商品表示数量，不返回独立的
`productId`、`productName` 或 `quantity` 字段。

```json
{
  "success": true,
  "items": [
    {
      "id": 3,
      "name": "Sony WH-1000XM5",
      "category": "耳机",
      "price": 2499.0,
      "description": "降噪蓝牙耳机",
      "stock": 80
    }
  ],
  "totalAmount": 2499.0,
  "itemCount": 1
}
```

购物车为空时仍返回 `success=true`、`items=[]`、`totalAmount=0.0` 和
`itemCount=0`，并额外返回 `message="购物车为空"`。

## 下一步建议
查看购物车后，可以：
- 使用 `checkout` 结算订单
- 继续添加更多商品（使用 add-to-cart）
