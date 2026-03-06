---
name: get-product-detail
description: 根据商品 ID 获取详细信息
version: 1.0
links:
  - name: add-to-cart
    description: 将商品加入购物车
---

# 商品详情技能

## 功能描述
根据商品 ID 获取该商品的完整详细信息。

## API 端点
```
GET /api/products/{id}
```

## 路径参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | integer | 是 | 商品 ID |

## 调用示例
```
GET /api/products/3
```

## 返回结构
```json
{
  "id": 3,
  "name": "Sony WH-1000XM5",
  "category": "耳机",
  "price": 2499.0,
  "description": "降噪蓝牙耳机，支持 LDAC 高清音质",
  "stock": 80
}
```

## 下一步建议
查看详情后，如果用户满意，使用 `add-to-cart` 加入购物车。
