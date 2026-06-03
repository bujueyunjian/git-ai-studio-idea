import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Gson 由 IntelliJ 平台在运行时提供,这里仅编译期可见,不重复打包(避免版本冲突)
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.10.1")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 不设上限:不随 IDE 大版本失效
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// ── 复用的 React 前端(webview/)构建产物拷进插件资源 /web ──
// 需先在 webview 下 `pnpm install`。已安装则随插件构建自动 `pnpm build`(打进 jar);
// 未安装(无 node_modules)则跳过,沿用已存在的 src/main/resources/web。
val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
val webviewDirFile = layout.projectDirectory.dir("webview").asFile
val nodeModulesFile = layout.projectDirectory.dir("webview/node_modules").asFile

// 解析 pnpm 绝对路径:PATH + nvm + 常见安装位。GUI/Gradle 进程拿不到 nvm 的 PATH,需显式定位,
// 并把 pnpm 所在目录(同处也有 node)注入 Exec 的 PATH,否则 pnpm.cjs 的 #!node shebang 找不到 node。
fun resolvePnpm(): File? {
    val exe = if (isWindows) "pnpm.cmd" else "pnpm"
    val home = System.getProperty("user.home").orEmpty()
    val dirs = mutableListOf<String>()
    System.getenv("PATH")?.split(File.pathSeparator)?.let { dirs += it }
    File("$home/.nvm/versions/node").takeIf { it.isDirectory }
        ?.listFiles()?.sortedByDescending { it.name }?.forEach { dirs += "${it.absolutePath}/bin" }
    dirs += listOf("/usr/local/bin", "/opt/homebrew/bin", "$home/.local/share/pnpm", "$home/Library/pnpm", "$home/.local/bin")
    return dirs.map { File(it, exe) }.firstOrNull { it.exists() }
}
val pnpmFile = resolvePnpm()

val buildWebUi by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the reused git-ai-studio React UI into src/main/resources/web"
    workingDir = webviewDirFile
    onlyIf { nodeModulesFile.exists() && pnpmFile != null }
    if (pnpmFile != null) {
        environment("PATH", pnpmFile.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty())
        commandLine(pnpmFile.absolutePath, "build")
    } else {
        commandLine(if (isWindows) "cmd" else "true") // 不会执行(onlyIf 已挡)
    }
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    dependsOn(buildWebUi)
}

tasks {
    wrapper {
        gradleVersion = "9.2.0"
    }
}
