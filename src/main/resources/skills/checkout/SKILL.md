---
name: checkout
description: 结算购物车中的商品并生成订单
version: 2.0
---

# 结算订单技能

## 功能描述
提交当前登录用户购物车中的所有商品，生成订单并清空购物车。用户身份通过请求头中的认证信息自动获取。

## API 端点
```
POST /api/products/checkout
```

## 请求参数（Query）
无参数，用户身份由后端从请求头的认证信息中自动获取。

## 认证说明
此 API 为受保护接口，需要用户登录认证。调用时：
- 直接调用即可，无需传入 userId
- 用户身份由后端从请求头的认证信息中自动获取
- AI Agent 调用时会自动透传当前用户的认证信息

## 调用示例
```
POST /api/products/checkout
```

## 返回结构
```json
{
  "success": true,
  "message": "订单已提交",
  "totalAmount": 5498.0,
  "itemCount": 2,
  "username": "user1"
}
```

## 业务逻辑
- 如果购物车为空，返回错误
- 成功结算后购物车会被清空
- 返回订单总金额和商品数量
