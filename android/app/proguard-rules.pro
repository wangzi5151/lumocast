# LumoCast
-keep class com.lumocast.core.** { *; }
-keepattributes Signature
# Compose 无反射依赖，默认规则即可
-dontwarn org.bouncycastle.**
