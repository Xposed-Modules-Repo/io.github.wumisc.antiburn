# antiburn 诊断日志云端（Cloudflare Pages Function + D1）

App 端 `LogUploader.java` 在用户同意后，把机型 / 系统 / 模块版本 + 诊断日志 POST 到
`https://antiburn-log.pages.dev/api/log`，由 Pages Function 写入 D1 数据库 `antiburn-logs`。

## 目录
- `public/`：静态占位（无业务页面）。
- `functions/api/log.js`：Pages Function，收 POST JSON、写 D1、限大小、基础 CORS。
- `schema.sql`：D1 建表 SQL（已在远程库执行过一次）。
- `wrangler.toml`：Pages 项目名与 D1 绑定（binding `DB`）。

## 当前线上
- Pages 项目：`antiburn-log`，生产域名 `https://antiburn-log.pages.dev`
- D1：`antiburn-logs`（region WNAM），binding `DB`
- 接口：`POST /api/log`，JSON 字段 `env{...}`、`ts`、`log`

## 重新部署改动
```
npm i -g wrangler        # 或用 /tmp 临时安装的 wrangler 4
wrangler login           # 已缓存 OAuth；过期则重新授权
cd cf
wrangler pages deploy public --project-name antiburn-log
```
表结构改动后：`wrangler d1 execute antiburn-logs --remote --file=schema.sql`。

## 隐私与配额
- 只采机型 / 品牌 / 厂商 / 设备 / 产品 / 系统版本 / SDK / 模块版本 / ABI；不含账号、昵称。
- 上传必须先经用户在 App 内同意，同意状态独立存一份 prefs，可在「功能 › 通用」撤回。
- 单次日志截断到 120k 字符；Function 受 Workers 免费额度限制。
- **版本挂钩清理**：每次落库成功后，删除 `module_code` 低于本次上传 versionCode 的旧版本记录（含历史未带版本号的行）；同版本与更新版本保留。旧设备回传旧版本号不会反向删掉更新版本的行。
- 现状为开放写（无上传令牌）。若出现被刷风险，后续可在 Function 加共享 token 校验，端侧请求头同步带上。
