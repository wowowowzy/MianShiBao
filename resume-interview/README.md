# AI 简历面试练习工具（demo）

面向 C 端求职者的 AI 简历面试练习工具：上传 Java 后端简历 → 生成覆盖简历技能点、按难度阶梯排布的笔试题 → 作答后逐题打分、错点讲解、标准/更优回答展示，沉淀个人能力画像。

## 技术栈

- **后端**：Spring Boot 4.1.1 + Spring AI 2.0.1（DeepSeek starter），JDK 17
- **数据库**：SQLite（本地文件，零部署）
- **简历解析**：Apache PDFBox（PDF）/ Apache POI（Word）提文本 → LLM 结构化；扫描件走智谱 GLM-4V-Flash 免费 OCR
- **前端**：Vue 3 + Vite + Element Plus
- **LLM**：DeepSeek（deepseek-chat，出题/评分/抽取）

## 目录结构

```
resume-interview/
├── backend/     # Spring Boot 单体，最终打成含前端静态资源的 fat jar
├── frontend/    # Vue3 页面（上传/出题/答题/报告）
└── scripts/     # 题库导入、embedding 生成等脚本
```

## 启动

```bash
# 后端（开发模式）
cd backend && mvn spring-boot:run

# 前端（开发模式）
cd frontend && npm install && npm run dev

# 生产交付形态：前端构建产物打进后端的 fat jar，java -jar 单进程跑
```

## ✅ 产品决策（与需求方已确认）

- 目标用户：C 端求职者；范围：Java 后端一个方向起步
- 题库来源：网抓素材（牛客/小林coding）+ 开源素材 → AI 清洗重写，流水线化（去重→补全→打标→质检）
- 评估口径：逐题打分 + 错点讲解 + "更完美回答"分级展示
- 场景：书面作答，不做 AI 语音/对话面试官