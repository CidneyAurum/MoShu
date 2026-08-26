# Room 与 WorkManager 使用生成代码和清单注册；其余由 AndroidX consumer rules 负责。
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keepclassmembers class app.moshu.journal.data.db.** { *; }
