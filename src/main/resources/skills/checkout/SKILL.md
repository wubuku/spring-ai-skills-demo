---
name: checkout
description: 结算购物车中的商品并生成订单
version: 1.0
---

# 结算订单技能

## 功能描述
提交购物车中的所有商品，生成订单并清空购物车。

## API 端点
```
POST http://localhost:8080/api/products/checkout
```

## 请求参数（Query）
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | integer | 是 | 用户 ID（默认使用 1） |

## 调用示例
```
POST /api/products/checkout?userId=1
```

## 返回结构
```json
{
  "success": true,
  "message": "订单已提交",
  "totalAmount": 5498.0,
  "itemCount": 2
}
```

## 业务逻辑
- 如果购物车为空，返回错误
- 成功结算后购物车会被清空
- 返回订单总金额和商品数量
