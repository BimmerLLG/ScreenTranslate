# 混淆规则（ProGuard）
# 定义哪些类和方法需要保留，哪些可以被混淆或移除

# ML Kit (Google 翻译库)
-keep class com.google.mlkit.** { *; }  # 保留 ML Kit 所有类
-dontwarn com.google.mlkit.**           # 忽略 ML Kit 警告

# OkHttp (网络请求库)
-dontwarn okhttp3.**                     # 忽略 OkHttp 警告
-dontwarn okio.**                        # 忽略 OkIO 警告
