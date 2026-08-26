# 墨枢 MoShu

墨枢是一款本地优先的 Android AI 记忆中枢，围绕“捕捉 → 整理 → 行动 → 回顾”组织个人记录。

## 主要能力

- 今日：文字与图片快速记录、灵感提示和处理状态。
- 记忆：时间线、置顶、全文搜索、分类/标签/情绪筛选。
- 行动：手工创建与 AI 提取待办，支持截止时间、提醒和来源跳转。
- 回顾：连续记录、活跃日历、分类占比、情绪趋势、周月总结与带来源问答。
- 本地优先：无账号、无云同步，API Key 由 Android Keystore 加密保存。
- BYOK：支持 OpenAI Chat Completions 兼容服务、非标准版本路径、完整自定义端点和自定义鉴权头。

## 环境

- Kotlin 2.0.21 / AGP 8.7.3 / JDK 17
- Jetpack Compose / Room / WorkManager / DataStore
- 最低 Android 8.0（API 26），目标 API 35
- 包名：`app.moshu.journal`
- 版本：`1.0.0`（versionCode 2）

## 构建

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug lintRelease
.\gradlew.bat assembleDebug assembleRelease
```

本地 AI 配置放在未跟踪的 `local.properties` 中；API Key 不会编入 Debug 或 Release APK。完整设计和数据迁移说明见 [DESIGN.md](DESIGN.md)。
