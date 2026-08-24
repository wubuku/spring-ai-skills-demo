# 前端构建可复现性与依赖安全加固规划

> **状态**: 已实施并完成验收
> **范围**: `frontend/` 的安装、构建支持文件、仓库忽略规则、生产依赖安全、
> 确定性检查和真实传统页面 LLM E2E
> **不包含**: AG-UI 协议、CopilotKit 工具执行逻辑或 CopilotKit 版本迁移；本轮不改变后端业务 API
> **规划日期**: 2026-08-24

## 1. 背景与问题定义

当前前端构建链由以下源码和生成物组成：

- `frontend/package.json` 的 `postinstall` 执行
  `scripts/transform-v2-css.mjs`；
- `frontend/app/layout.tsx` 直接导入 `patches/copilotkit-v2-v3.css`；
- `frontend/next.config.js` 将 CopilotKit v2 CSS 解析到同一个补丁文件；
- `frontend/package-lock.json` 固定 CopilotKit 1.60.2 和 Mermaid 11.15.0。

但根 `.gitignore` 当前忽略整个 `frontend/scripts/` 和 `frontend/patches/`。这些文件在本地
工作区存在，所以本地 `npm run build` 可以通过；它们不在 Git 历史中，新的 clone 运行
`npm ci` 时可能出现以下失败：

1. `postinstall` 找不到 `scripts/transform-v2-css.mjs`；
2. 即使使用 `npm ci --ignore-scripts`，`layout.tsx` 找不到被导入的 CSS；
3. 文档要求读者检查“被忽略的支持文件”，但没有让仓库本身具备可复现交付能力。

这不是纯文档问题，而是 Demo 交付边界错误：源码、依赖锁文件和构建所需补丁没有形成
同一个可复制的版本单元。

可复现性实施后的全新导出检查还暴露了第二类问题：

- `next@15.1.6` 存在公开的 critical/high 安全公告；
- 直接依赖 `undici@8.3.0` 受多个 HTTP、TLS、缓存和 WebSocket 公告影响；
- `package-lock.json` 混用 npm 官方 registry 与 `registry.npmmirror.com` 的
  `resolved` 地址，干净导出的 `npm ci` 在镜像下载阶段长时间无进展；
- 2026-08-24 使用 npm 官方 registry 执行 `npm audit --omit=dev` 的基线为
  23 个生产依赖问题：1 critical、6 high、10 moderate、6 low。

其中部分高危问题来自旧锁文件未选择依赖声明范围内的新补丁版本；Next 15 当前维护回补
版本为 `15.5.23`。Next 16 是独立的大版本迁移，不应为了本轮修复直接引入。

## 2. 目标与非目标

### 2.1 目标

1. 全新 clone 在安装前端依赖后，能够找到构建脚本和 CSS 补丁。
2. `npm ci` 的 `postinstall` 可以从锁定的 CopilotKit 依赖重新生成补丁。
3. 生成结果稳定，不因重复运行造成不必要的工作区差异。
4. 增加一个秒级确定性仓库检查，明确验证必需支持文件、入口引用和 postinstall 约定。
5. 更新稳定文档，读者不再需要从被忽略的本地文件恢复构建链。
6. 完成后端确定性回归、前端构建/Mock E2E，以及用户已授权的真实 LLM 传统页面闭环验证。
7. 消除生产依赖中的 critical/high 审计项，并让锁文件只使用 npm 官方 registry。
8. 固定本轮明确不迁移的 CopilotKit 1.60.2 依赖组，避免重建锁文件时隐式改变 AG-UI 行为。

### 2.2 非目标

- 不升级 CopilotKit、React 的直接依赖版本，也不迁移到 Next 16。
- 默认只在上游已声明的 semver 范围内刷新传递依赖；唯一例外是 3.4 节记录的
  AI SDK Undici 定向安全覆盖。
- 不重新设计 CSS 转换器，不改动已有视觉样式。
- 不修复 AG-UI 登录后 BFF token 状态同步等历史问题。
- 不把真实 LLM 测试作为离线测试的替代品。

## 3. 实施决策

### 3.1 跟踪源脚本和生成补丁

推荐从 `.gitignore` 移除对 `frontend/scripts/` 和 `frontend/patches/` 的宽泛忽略，
将当前构建实际依赖的以下文件纳入主仓库：

- `frontend/scripts/transform-v2-css.mjs`
- `frontend/patches/copilotkit-v2-v3.css`

理由：

- 源脚本是 `npm ci` 的直接入口，必须版本化；
- CSS 补丁是 `layout.tsx` 的直接导入目标，版本化后即使安装脚本被禁用也能让构建错误
  更快暴露，且能支持离线/受限环境的构建；
- 依赖版本已由 `package-lock.json` 固定，补丁生成结果具备可追溯输入。

`frontend/patches/stubs/mermaid-core-stub.mjs` 当前不是 `next.config.js`、源码或
`package.json` 的实际依赖。它保留在本地不影响本轮，但稳定文档不再把它描述为必需交付
文件；若未来重新使用，应在同一变更中恢复引用并纳入跟踪。

### 3.2 增加仓库可复现性检查

新增 `frontend/tests/repository-reproducibility.mjs`，只做确定性检查：

- 必需文件存在且是普通文件；
- `package.json` 的 `postinstall` 仍引用转换脚本；
- `next.config.js` 和 `app/layout.tsx` 仍引用已存在的 CSS 补丁；
- CSS 补丁不是空文件；
- 以 `CHECK_ONLY=true` 运行转换器，确认生成物与版本库文件一致；检查过程不得原地
  改写工作区文件。

在 `frontend/package.json` 增加 `test:repository` 脚本。该检查不访问模型、数据库、
浏览器或后端，适合在 `npm run build` 前秒级失败。

### 3.3 文档事实源

同步更新：

- `AGENTS.md` 的前端构建支持文件说明；
- `docs/DEVELOPMENT.md` 的安装和构建说明；
- `docs/HARNESS.md` 的前端验证矩阵；
- `docs/troubleshooting.md` 的前端构建故障排查；
- `frontend/README.md` 的 CSS/构建支持文件说明；
- 本规划文档的状态和验证记录。

文档应说明：支持文件由仓库跟踪，`postinstall` 会校验/重建生成 CSS；不再要求读者从
ignored 文件或其他工作区恢复它们。

### 3.4 生产依赖安全与锁文件策略

本轮采用以下低风险默认值：

1. `next` 和 `eslint-config-next` 从 `15.1.6` 同步升级到 Next 15 维护回补版本
   `15.5.23`，不跨到 Next 16。
2. `undici` 固定为已覆盖当前公告修复的 `8.10.0`。该版本要求 Node `>=22.19.0`，
   因此在 `package.json` 的 `engines.node` 和稳定开发文档中显式声明该门槛。
3. `next@15.5.23` 仍声明旧的 `postcss@8.4.31` 和 `sharp@^0.34.3`。使用 npm
   `overrides` 将这两个 Next 内部依赖固定到安全版本 `postcss@8.5.26` 和
   `sharp@0.35.3`；生产构建、干净安装和页面启动验证必须证明覆盖没有破坏兼容性。
4. CopilotKit 1.60.2 固定的 `@ai-sdk/provider-utils@3.0.32` 声明
   `undici@^5.29.0`，而 Undici 5.x 已没有覆盖当前 high 公告的补丁版本。使用精确
   override 将该包的 Undici 固定为 `6.28.0`；临时 lockfile 验证表明三处旧
   `undici@5.29.0` 会统一解析到 6.28.0，生产审计为 0 critical/high。
   这是跨 Undici major 的定向覆盖，必须由 `npm ci`、TypeScript、production build
   和前端启动验证证明兼容；失败时按 4.2 节只撤销该 override 并将高危风险标记为阻塞。
5. 将四个 CopilotKit 直接依赖固定为当前已验证的 `1.60.2`，不利用原有 `^1.60.2`
   范围隐式升级。重新生成锁文件时，只允许其传递依赖在上游声明范围内更新。
6. 使用 npm 官方 registry 从仅含 `package.json` 的干净临时目录重建 lockfile，使
   `resolved` 地址不再依赖机器本地的镜像配置。仓库检查应拒绝
   `registry.npmmirror.com`。

应用上述覆盖的临时干净 lockfile 审计结果为 11 项残余：0 critical、0 high、
6 moderate、5 low。CopilotKit 1.60.2 的 `react-syntax-highlighter`、runtime `uuid`
及其他可选 provider 仍会产生 moderate/low 项。进一步消除这些问题需要升级更多
AG-UI/CopilotKit 运行依赖，属于本轮明确排除的协议链路变更；本轮接受已记录的
moderate/low 残余，但不接受任何 critical/high。
后续 CopilotKit 升级必须单独规划，并覆盖 Next/CopilotKit 的实际 AG-UI 交互。

## 4. 修改范围

### 4.1 文件

预期修改或新增：

| 文件 | 修改 |
|---|---|
| `.gitignore` | 移除 `frontend/scripts/`、`frontend/patches/` 宽泛忽略 |
| `frontend/scripts/transform-v2-css.mjs` | 纳入 Git；增加 `CHECK_ONLY=true` 的只读检查模式 |
| `frontend/patches/copilotkit-v2-v3.css` | 纳入 Git，作为可复现生成物 |
| `frontend/tests/repository-reproducibility.mjs` | 新增确定性仓库检查 |
| `frontend/package.json` | 增加 `test:repository`；升级/固定依赖、Node engines 和 overrides |
| `frontend/package-lock.json` | 使用 npm 官方 registry 重建并刷新安全传递依赖 |
| `AGENTS.md` | 更新当前跟踪边界和验证命令 |
| `docs/DEVELOPMENT.md` | 更新安装/构建说明 |
| `docs/HARNESS.md` | 增加仓库检查及真实验证顺序 |
| `docs/troubleshooting.md` | 删除 ignored 文件恢复假设 |
| `frontend/README.md` | 更新支持文件说明 |
| 本文 | 记录实施结果、检查轮次和验证证据 |

不预期修改 Java 生产代码、Skill 文档、AG-UI 端点和子模块。

### 4.2 回滚边界

可复现性部分可以通过恢复 `.gitignore` 两条规则、删除新增检查和移除两个 Git 跟踪文件
回滚。依赖安全部分可以独立恢复 `package.json` 中的 Next、Undici、CopilotKit 固定版本、
`engines` 和 `overrides`，并恢复对应旧 lockfile；不需要回滚 Java 代码或业务数据。

若某个 override 导致安装、构建或启动不兼容，优先只撤销该 override，并把对应上游
critical/high 风险记录为阻塞，而不是跨到 Next 16 或改变 CopilotKit/AG-UI 链路。
本轮不会改变运行时 API、数据库 schema、认证契约或模型提示词。

## 5. 验收设计

### 5.1 修改前的确定性基线

在修改前确认当前事实：

```bash
git status --short --branch
git status --short --ignored frontend/scripts frontend/patches
git ls-files frontend/scripts frontend/patches
```

预期能够证明支持文件存在但未被 Git 跟踪，并且 `.gitignore` 覆盖了它们。

### 5.2 修改后的离线硬门槛

按以下顺序执行：

```bash
git diff --check
bash -n dev.sh
mvn clean compile test-compile
mvn clean test
cd frontend
npx tsc --noEmit
npm run test:repository
npm run build
npm run test:skills
npm run test:e2e:traditional:mock
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

前端测试只使用文件、构建产物、Mock HTTP 和 DOM/网络断言；不使用截图，不访问真实
模型或数据库。审计命令必须以 `0` 退出，且输出中 critical/high 均为 `0`；允许保留
已记录且属于 CopilotKit 迁移边界的 moderate/low 项。

### 5.3 全新 clone/安装模拟

在临时目录生成只包含当前 Git 树和子模块指针的工作副本，确认：

1. 必需支持文件出现在副本中；
2. `npm ci --registry=https://registry.npmjs.org` 能执行 `postinstall`；
3. `CHECK_ONLY=true node scripts/transform-v2-css.mjs` 通过且不修改工作区；
4. 转换结果与版本化 CSS 一致；
5. `npm run build` 通过；
6. lockfile 不含 `registry.npmmirror.com`，且安装不依赖当前工作区已有 `node_modules`。

若网络或 npm registry 不可用，可以用 `npm ci --offline`（当缓存可用）辅助诊断，
但不能替代一次成功的官方 registry 干净安装；将外部网络阻塞与代码失败分开记录。

### 5.4 真实 LLM 验证

用户已经明确允许使用主工作区 `.env` 中的真实凭证。因此在所有离线门槛通过后，执行
两个互补的最小真实场景：

1. 先执行独立 provider 测试，确认 OpenAI-compatible chat completions 和
   tool-calling 契约实际可用，命令必须显示 `Tests run: 1`：

   ```bash
   set -a && source .env && set +a
   RUN_LIVE_LLM_TESTS=true \
     mvn -Dtest.excluded-groups=container \
     -Dtest=OpenAiCompatibleApiLiveTest test
   ```

2. 使用 `./dev.sh --backend-only` 从 `.env` 启动后端；
3. 观察临时日志，确认服务健康且没有启动/认证/数据库错误；
4. 在 `frontend/` 执行 `npm run test:e2e:traditional`；
5. Playwright 通过内嵌传统 Web UI 完成登录、认证请求、普通 `/api/chat/text`、
   Skill/tool loop 和商品结果 DOM 断言；
6. 观察模型调用期间日志，遇到 401/403、重复工具调用、超时或无响应立即停止并分类；
7. 最后执行 `./dev.sh --stop`，确认端口释放。

真实 LLM 测试的证据是 HTTP、DOM、JSON 和自动化断言，不打印 API key，不使用截图。
provider 测试证明基础聊天和工具调用协议；传统 UI 测试证明普通 Agent、Skill/API 和
浏览器 DOM 的跨端链路。二者都不能替代 `test:repository`、后端 Mock 集成测试或前端
Mock E2E。

## 6. 规划检查流程

每次实质性扩展规划后重新执行连续三轮检查。当前依赖安全扩展后的检查范围固定为：

1. **版本与安全事实**：检查官方版本、引擎约束、审计路径、override 和 AG-UI 排除边界；
2. **交付与兼容性**：检查全新 clone、官方 registry、lockfile、postinstall、构建和回滚；
3. **验证与文档**：检查硬门槛、审计退出条件、真实 LLM 后置顺序、文档导航和 DOM/网络证据。

只要发现会影响正确性、可复现性、安全、兼容性或验证可信度的问题，就修改规划并将
计数器重置为 `0`。连续三轮无修改后，才开始实施。

### 6.1 实际检查记录

检查时间均为 2026-08-24 CST：

1. 初次事实检查发现原计划让仓库检查直接运行转换器，可能在“检查”阶段原地改写 CSS；
   规划已改为 `CHECK_ONLY=true` 只读模式，计数器重置为 `0`。
2. 第 1 轮检查范围为文件路径、源码引用、`.gitignore` 和 npm 生命周期；未发现问题，
   未修改规划，计数器为 `1`。
3. 第 2 轮检查范围为全新 clone、`npm ci`、`--ignore-scripts`、生成物跟踪和回滚边界；
   未发现问题，未修改规划，计数器为 `2`。
4. 第 3 轮检查范围为后端/前端门槛、Mock Playwright、真实 LLM 后置顺序、DOM/网络证据
   和文档入口；未发现问题，未修改规划，计数器为 `3`，规划审查结束。
5. 全新导出安装发现生产依赖安全问题和镜像 registry 可用性问题，规划扩展到依赖安全，
   原检查结论不再覆盖完整范围，计数器重置为 `0`。
6. 依赖安全扩展后的交付检查发现回滚边界未覆盖版本、engines、overrides 和 lockfile；
   已补齐局部回滚策略，计数器重置为 `0`。
7. 随后的验证检查发现最终真实验收只列出传统 Web UI，未明确要求重跑独立 provider
   tool-calling 测试；已补齐两个互补真实场景，计数器再次重置为 `0`。
8. 实施阶段的干净 lockfile 审计发现 AI SDK 固定的 Undici 5.x 无安全补丁可选；
   已验证并规划 `@ai-sdk/provider-utils@3.0.32 -> undici@6.28.0` 定向覆盖，
   计数器重置为 `0`。

## 7. 实施后记录

### 7.1 已实施

- 移除 `.gitignore` 对整个 `frontend/scripts/`、`frontend/patches/` 的宽泛忽略；
  历史 `frontend/patches/stubs/` 仍保持忽略。
- 将 `transform-v2-css.mjs` 和 `copilotkit-v2-v3.css` 纳入主仓库版本边界。
- 转换器增加 `CHECK_ONLY=true` 只读模式；生成物缺失或过期时返回非零状态。
- 新增 `npm run test:repository`，验证文件存在、postinstall、Next.js/layout 引用和
  CSS 生成物一致性，同时约束 Node、固定依赖、overrides、官方 registry lockfile 和
  Next.js tracing root。
- Next.js/`eslint-config-next` 升级到 15.5.23，直接 Undici 固定为 8.10.0，四个
  CopilotKit 包固定为 1.60.2；Next 的 PostCSS/Sharp 和旧 AI SDK 的 Undici 使用
  3.4 节记录的精确安全覆盖。
- `package-lock.json` 从 npm 官方 registry 干净重建，不再包含
  `registry.npmmirror.com`。
- `next.config.js` 将 `outputFileTracingRoot` 固定在 `frontend/`，避免父目录其他
  lockfile 改变构建追踪边界。
- 三套 Playwright 脚本共用浏览器启动帮助器；默认仍用 Playwright Chromium，
  `PLAYWRIGHT_BROWSER_CHANNEL=chrome` 可在下载源不可用时使用系统 Chrome。
- 传统页面声明空 favicon，消除匿名加载时 `/favicon.ico` 被安全规则拒绝产生的
  无意义 403 console error；Mock server 同时明确处理 favicon 请求。
- 更新 Agent、开发、验证、排障和前端文档，不再要求从 ignored 文件或其他工作区恢复
  构建支持文件，并记录 Node、审计、tracing root 和浏览器 fallback。

### 7.2 已通过验证

- `git diff --check`、`bash -n dev.sh`。
- `mvn clean compile test-compile`。
- `mvn clean test`：28 个测试全部通过。
- `npm run test:repository`，并通过 CSS 前后 hash 证明检查模式没有改写生成物。
- `npx tsc --noEmit`、`npm run build`。
- 使用系统 Chrome fallback 执行 `npm run test:skills` 和
  `npm run test:e2e:traditional:mock`，两套 DOM/网络断言均通过。
- `npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org`
  以 `0` 退出；生产依赖为 0 critical、0 high、6 moderate、5 low。
- 从暂存索引导出的临时副本不包含 `node_modules`、`.env` 或 `.git`；使用 npm 官方
  registry 执行 `npm ci`、`npm run test:repository`、`npx tsc --noEmit` 和
  production build 全部通过。
- 使用 `.env`、PostgreSQL profile 和真实 OpenAI-compatible `grok-4.5` 启动后端；
  数据库、6 个 Skills 和 24 个 API index 正常加载。
- 使用系统 Chrome 执行 `npm run test:e2e:traditional`，真实 Playwright 闭环通过，
  覆盖登录、`/api/auth/verify`、真实 `/api/chat/text`、Skill/tool loop 和商品结果
  DOM；不使用截图，最终浏览器 console 无错误。
- `OpenAiCompatibleApiLiveTest` 在最终代码上实际执行 1 个测试并通过，覆盖基础聊天和
  tool-calling，未被标签跳过。

### 7.3 最终交付边界

- 本轮没有升级 CopilotKit、改动 AG-UI 协议或扩展 AG-UI 端点覆盖。
- 6 个 moderate 和 5 个 low 属于 CopilotKit 1.60.2 迁移边界，已明确接受；后续升级
  必须单独规划并验证 AG-UI 行为，不能在本轮用 `npm audit fix --force` 隐式完成。
- 三轮限定范围只读审查只用于收敛交付，不替代以上自动化验证。
- Git 交付必须包含主仓库和两个子模块的干净状态证明。
