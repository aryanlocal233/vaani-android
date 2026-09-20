# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp

# Room
-keep class androidx.room.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# App data models (parsed via JSON)
-keep class com.vaani.android.data.** { *; }
-keep class com.vaani.android.translation.** { *; }
