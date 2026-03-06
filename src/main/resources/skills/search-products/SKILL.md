---
name: search-products
description: 搜索商品目录，支持关键词、分类、价格范围过滤
version: 1.0
links:
  - name: get-product-detail
    description: 获取单个商品的详细信息
  - name: add-to-cart
    description: 将商品加入购物车
---

# 商品搜索技能

## 功能描述
调用商品搜索 API，根据用户需求筛选商品列表。

## API 端点
```
GET /api/products
```

## 请求参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 否 | 搜索关键词（匹配商品名称或描述） |
| category | string | 否 | 商品分类（如：手机、平板、耳机） |
| priceMin | number | 否 | 最低价格 |
| priceMax | number | 否 | 最高价格 |

## 调用示例
```json
GET /api/products?keyword=耳机&priceMax=3000
```

## 返回结构
```json
[
  {
    "id": 3,
    "name": "Sony WH-1000XM5",
    "category": "耳机",
    "price": 2499.0,
    "description": "降噪蓝牙耳机",
    "stock": 80
  }
]
```

## 下一步建议
获取到商品列表后，可以：
- 使用 `get-product-detail` 查看某个商品的详细信息
- 使用 `add-to-cart` 直接将商品加入购物车
