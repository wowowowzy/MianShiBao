# 面试宝（MianShiBao）

面向 C 端求职者的 **AI 简历面试练习工具**：上传简历 → 生成覆盖你技能点、按难度阶梯排布的笔试题 → 作答后逐题打分、错点讲解、给出标准/更优回答，沉淀个人能力画像。

一个仓库两套端，共用同一个后端：

| 目录 | 是什么 | 技术栈 |
|---|---|---|
| [`resume-interview/`](resume-interview/) | 后端 + Web 前端（单体） | Spring Boot 4.1.1、Java 17、Spring AI、SQLite、Vue 3 + Vite + Element Plus |
| [`wxFrontend/`](wxFrontend/) | 原生微信小程序端 | 微信小程序原生（零编译，开发者工具直接打开） |

> 线上地址：<https://wowowzy.art>

---

## 功能概览

- **简历解析**：PDF / Word / TXT 提文本 → LLM 结构化出「技能 + 项目经历 + 能力画像」；扫描件走 OCR
- **智能出题**：按简历技能点 + 岗位方向出题，难度阶梯排布；支持「常规」与「大厂挑战」两种模式
- **作答与判分**：逐题书写作答，交卷后逐题打分、命中/错点讲解、标准回答与更优回答分级展示
- **个人中心**：历次解析 / 作答 / 出题记录、技能画像、学习路线、复用旧卷
- **RAG 召回**：题库向量化（sqlite-vec）+ rerank 精排，提升出题相关性
- **多端一致**：Web 与小程序共用同一套后端接口与判分口径

---

## 目录结构

```
E:\Coding
├── .gitattributes            # 统一换行（*.bat 固定 CRLF 等）
├── .gitignore                # 全仓忽略规则（密钥 / 数据 / 构建产物）
├── README.md                 # 本文件
│
├── resume-interview/         # 后端 + Web 前端
│   ├── backend/              # Spring Boot 单体（最终打成含前端静态资源的 fat jar）
│   ├── frontend/             # Vue3 页面（上传 / 出题 / 答题 / 报告 / 管理台）
│   ├── data/                 # SQLite 数据文件（不入库）
│   ├── .env                  # 真实密钥（不入库）
│   ├── .env.example          # 配置模板（入库）
│   ├── start.bat / stop.bat  # 一键起/停（开发模式）
│   └── README.md
│
└── wxFrontend/               # 原生微信小程序
    ├── pages/                # index / login / quiz / report / me
    ├── utils/                # config / request / api / poll
    └── README.md
```

---

## 快速开始

### 1. 配置密钥（`.env`）

真实密钥**不入库**，先复制模板再填写：

```bat
copy resume-interview\.env.example resume-interview\.env
```

需要填的键（`resume-interview/.env`）：

| 键 | 用途 |
|---|---|
| `DEEPSEEK_API_KEY` / `DEEPSEEK_MODEL` / `DEEPSEEK_BASE_URL` | 出题 / 判分 / 抽取（主力 LLM） |
| `ZHIPU_API_KEY` / `ZHIPU_OCR_MODEL` / `ZHIPU_BASE_URL` | 扫描件简历 OCR |
| `DASHSCOPE_API_KEY` / `DASHSCOPE_EMBEDDING_MODEL` / `DASHSCOPE_BASE_URL` | 题库向量化（RAG） |
| `RERANK_BASE_URL` | 召回结果精排 |
| `WECHAT_MINIAPP_APPID` / `WECHAT_MINIAPP_SECRET` | 小程序静默登录（AppSecret 仅服务端） |
| `JWT_SECRET` | 登录令牌签名密钥（HS256，**≥32 字节**，`openssl rand -base64 48` 生成） |

### 2. 起后端 + Web 前端（开发模式）

```bat
cd resume-interview
start.bat
```

- 后端：`cd backend && mvn spring-boot:run`（<http://localhost:8080>）
- 前端：`cd frontend && npm install && npm run dev`（<http://localhost:5173>）

### 3. 起小程序

微信开发者工具 → 导入项目 → 选择 `wxFrontend/` 目录（AppID 已配置）。
后端地址在 `wxFrontend/utils/config.js` 的 `BASE_URL`；真机预览需在微信公众平台
「开发 → 开发管理 → 开发设置 → 服务器域名」把 `https://wowowzy.art` 加入 **request 合法域名**。

### 4. 生产交付形态

Web 端：`frontend` 构建产物打进 `backend` 的 fat jar，`java -jar` 单进程跑（详见 `resume-interview/README.md`）。

---

## 安全说明

- 所有密钥/端点均通过 `resume-interview/.env` 注入，**`.env` 已被 `.gitignore` 忽略**，仓库只保留 `.env.example` 模板。
- `JWT_SECRET` 是登录令牌的签名密钥：公开泄露 = 任何人都能伪造登录令牌。请务必各环境单独配置。
- 题库原始素材（第三方版权 PDF）与本地数据库、`node_modules`、`target`、小程序个人配置均不入库。