// 顶层构建脚本：只声明插件，不 apply。
// 依赖全部来自本地 Gradle 缓存（AGP 8.9.1），因此可以 --offline 构建。
plugins {
    id("com.android.application") version "8.9.1" apply false
}
