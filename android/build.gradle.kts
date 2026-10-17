// 버전은 함께 검증된 조합으로 고정한다(supabase-kt 3.1.4 = Kotlin 2.1.20 · Ktor 3.1.2). 올릴 때는 묶어서 올린다.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
