# wxFrontend — AI 简历面试练习工具（微信小程序）

web 端 `frontend/`（Vue 3）的**原生微信小程序**版本，零编译、微信开发者工具直接打开即运行。
登录采用**微信静默登录**（`wx.login` 取 code → 后端 `/api/auth/wechat-login` 换 openid 自动建号），无需图形验证码。

## 目录结构

```
wxFrontend/
├── project.config.json      # 小程序工程配置（AppID: wx20139b3693e6c162）
├── app.js / app.json / app.wxss
├── utils/
│   ├── config.js            # BASE_URL 与缓存 key、岗位/方向字典
│   ├── request.js           # wx.request / wx.uploadFile → Promise，自动带 token、401 跳登录
│   ├── api.js               # 与后端契约对齐的全部接口封装
│   └── poll.js              # 轮询工具（组卷/解析/判分等异步任务）
└── pages/
    ├── login/               # 微信一键登录（App 启动即静默尝试）
    ├── index/               # 首页：上传简历 → 能力画像 → 出题（tabBar）
    ├── quiz/                # 答题页
    ├── report/              # 评分报告页
    └── me/                  # 个人中心：解析/作答/出题记录（tabBar）
```

## 运行步骤

1. **打开工程**：微信开发者工具 → 导入项目 → 选择本目录 `wxFrontend/`，AppID 已配置为 `wx20139b3693e6c162`（如无权限可改用「测试号」，但静默登录需真实 AppID + AppSecret）。
2. **配置合法域名**（微信公众平台后台 → 开发 → 开发管理 → 开发设置 → 服务器域名）：
   - request 合法域名：`https://wowowzy.art`
   - 开发者工具勾选「不校验合法域名」可本地联调，真机预览必须在后台配置该域名。
3. **服务端配好微信参数**：编辑后端仓库根目录的 `E:\Coding\resume-interview\.env`，填入小程序密钥（AppSecret）：
   ```
   WECHAT_MINIAPP_APPID=wx20139b3693e6c162   # 已填好，无需改
   WECHAT_MINIAPP_SECRET=<粘贴小程序密钥，平台「开发设置」获取>
   ```
   即把 `WECHAT_MINIAPP_SECRET=` 后留空的那行补上密钥即可（AppID 已固定）。
4. 编译运行：**开发模式**直接点「编译」；上传体验版/发布用「上传」→ 真机扫码验证。

## 与传统账号密码的差异

- 微信静默登录免去「用户名/密码 + 图形验证码」，首次进小程序 `onLaunch` 即通过 `wx.login` 静默换取后端 JWT。
- 后端新增 `POST /api/auth/wechat-login`（白名单免鉴权），用 `jscode2session` 以 openid 找号/建号（openid 列唯一索引，重复登录命中同一账号），复用原有 JWT 鉴权与每日配额。

## 当前已实现功能

- 登录：微信一键登录（静默）＋自动续期由后端 JWT（7 天）管理。
- 首页：常规 / 大厂挑战 模式切换；选文件(`chooseMessageFile`)或图片(扫描件)上传简历 → 后台解析轮询 → 能力画像卡片；一键出题/大厂挑战、学习路线。
- 答题：逐题作答（草稿按试卷 id 本地落盘防误关丢失）、进度条、交卷时空题二次确认。
- 报告：总分仪表 + 逐题要点命中/错点/标准回答/拆解，与 web 端一致。
- 我的：三个统计卡 + 解析/作答/出题记录选项卡；查看技能、学习路线、删除、用这份简历重新出题、复用旧卷、查看历史报告。

## 上传简历说明

简历通过 `wx.chooseMessageFile`（从聊天文件选择 PDF/Word/TXT/图片）或 `wx.chooseMedia`（相册/拍照图片）选择，
`wx.uploadFile` 到 `/api/resume/parse`（name=file，formData 带 majorDomain/role）。
小程序对大文件上传有 10s 限制的注意事项请关注平台文档；一般简历 PDF 均在限制内。