# BouncyCastle：Argon2id 走 Argon2BytesGenerator，反射面较大，整体保留。
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization：@Serializable 类的伴生序列化器由编译器生成后经反射查找。
# R8 破坏序列化是"debug 正常 release 崩"最常见的来源（计划.md §15.13）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lc33.tokenvault.**$$serializer { *; }
-keepclassmembers class com.lc33.tokenvault.** {
    *** Companion;
}
-keepclasseswithmembers class com.lc33.tokenvault.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose 的类型安全路由：@Serializable 的路由对象靠反射还原
-keep class com.lc33.tokenvault.ui.shell.** { *; }

# OkHttp / Okio 自带 consumer rules，这里只压掉可选依赖的警告
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
