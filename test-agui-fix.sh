#!/bin/bash
# Test AG-UI endpoint with tool calls after fix
set -e

echo "=== Getting auth token ==="
TOKEN=$(curl -s http://localhost:8080/api/auth/login \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","password":"password1"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

echo "Token: ${TOKEN:0:20}..."

echo ""
echo "=== Sending AG-UI request ==="
curl -s -N -X POST http://localhost:8080/api/agui \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "threadId": "test-fix-001",
    "runId": "run-001",
    "messages": [{"role": "user", "content": "查询所有商品列表"}],
    "tools": [],
    "context": []
  }' 2>&1 | head -200

echo ""
echo "=== Test Complete ==="
