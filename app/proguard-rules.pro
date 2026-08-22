# PageKit release 混淆规则（R8）

# ---------- kotlinx-serialization ----------
# 序列化器经伴生对象反射查找；keep 生成的 serializer 与可序列化 DTO 本体
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kenjc.pagekit.**$$serializer { *; }
-keepclassmembers class com.kenjc.pagekit.** { *** Companion; }
-keepclasseswithmembers class com.kenjc.pagekit.** { kotlinx.serialization.KSerializer serializer(...); }

# ---------- flexmark（html2md，ServiceLoader 扩展） ----------
-keep class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**

# ---------- MCP Kotlin SDK / ktor（反射注册 + 协程内部） ----------
-keep class io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# ---------- AIDL（binder IPC 桩类按名反射） ----------
-keep class com.kenjc.pagekit.profile.IProfileWorker** { *; }
-keep class com.kenjc.pagekit.profile.**_Stub { *; }
-keep class com.kenjc.pagekit.profile.**_Stub$Proxy { *; }

# ---------- WebView JS 桥（addJavascriptInterface 按名反射） ----------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- jsoup 引用的编译期注解（运行时不存在，安全忽略） ----------
-dontwarn javax.annotation.**

# ---------- WorkManager（反射实例化 Worker） ----------
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ---------- 崩溃可读性：保留行号与源文件名 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
