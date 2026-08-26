# 墨枢（MoShu）1.0 设计与工程说明

> 应用标识：`app.moshu.journal`  
> 产品定位：面向个人用户的本地优先 AI 记忆中枢  
> 隐私姿态：无账号、无云同步、BYOK，图片默认不参与 AI  
> 文档版本：2026-08-26 · v1.0.0

## 产品闭环

墨枢围绕“捕捉 → 整理 → 行动 → 回顾”工作：

- 今日：文字和图片快速记录、灵感提示、当日状态与近期记忆。
- 记忆：时间线、置顶、全文搜索、分类/标签/情绪筛选和人工修正。
- 行动：手工创建或 AI 提取，按逾期/今天/以后/完成分组，并保留来源记录。
- 回顾：连续记录、活跃日历、分类占比、情绪趋势、周月总结和带来源的“问墨枢”。

## 体验与视觉

- “苹果极简 × 东方墨韵”：宣纸白、玄墨黑、朱砂强调色，克制阴影与统一圆角。
- 支持系统、浅色、深色主题，边到边布局；宽屏使用导航栏，手机使用底部导航。
- 三步首次引导明确本地存储、BYOK 和图片上传边界。
- 简体中文优先，关键操作具备空状态、处理中、失败与重试反馈。

## 数据与系统能力

- Room schema v3；保留 v2 数据并提供 `MIGRATION_2_3` 和迁移仪器测试。
- 记录和行动使用稳定 UUID；支持更新时间、置顶、AI 状态、人工修改保护、完成/提醒时间。
- 附件导入应用私有目录，校正方向、重编码去元数据并限制尺寸和数量。
- FTS4 本地全文检索；AI 周/月回顾结果缓存。
- WorkManager 唯一任务完成 AI 整理，支持网络约束、进程恢复、降级和重试。
- 每日提醒和待办提醒均使用非精确窗口，不申请精确闹钟权限。
- `.moshu` 普通备份支持按 UUID 合并去重；另支持可读 Markdown 导出。所有导出均排除 API Key，备份不加密。

## AI 接入

- 使用 OpenAI Chat Completions 兼容协议，API Key 经 Android Keystore AES/GCM 加密保存。
- 支持 DeepSeek、OpenAI、基元律动、通义千问、智谱、Kimi 和 Ollama 快捷模板。
- 地址可填写根域名、带任意版本的基础路径或完整 `/chat/completions` 地址；高级设置可将任意 HTTP(S) 地址作为完整请求端点。
- 支持自定义鉴权 Header 和前缀，适配更多兼容网关。
- 可单独配置视觉模型。只有用户显式开启时图片才会发送；视觉请求失败自动回退到纯文本。

## 技术栈

- Kotlin 2.0.21、AGP 8.7.3、Gradle 8.9、JDK 17。
- 单 Activity、Jetpack Compose、Material 3、Navigation Compose。
- Room + KSP、DataStore、WorkManager、Photo Picker、Android Keystore。
- 最低 Android 8.0（API 26），目标 API 35。
- Release 启用 R8、资源压缩和备份规则；版本号 `1.0.0`、`versionCode 2`。

## 构建与验收

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug lintRelease
.\gradlew.bat assembleDebug assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

Release 构建默认写入空 AI 配置；正式发布前应使用 Android Studio 或发布流水线签名。
