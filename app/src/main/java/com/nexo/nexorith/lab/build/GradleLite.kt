package com.nexo.nexorith.lab.build

data class Dependency(
    val configuration: String,
    val group: String,
    val artifact: String,
    val version: String?
) {
    val coordinate: String
        get() = if (version != null) "$group:$artifact:$version" else "$group:$artifact"
}

data class ProjectConfig(
    val applicationId: String?,
    val versionCode: Int?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val dependencies: List<Dependency>
)

/**
 * Gradle-lite: pembaca subset build.gradle buatan sendiri, bukan Gradle asli.
 * Mendukung applicationId, versionCode, versionName, minSdk, targetSdk, compileSdk
 * dan baris implementation/api/compileOnly/runtimeOnly 'group:artifact:version'.
 */
object GradleLite {
    private val APP_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")
    private val DEPENDENCY = Regex(
        "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*['\"]([^'\":\\s]+):([^'\":\\s]+)(?::([^'\":\\s]+))?['\"]",
        RegexOption.MULTILINE
    )

    fun isValidApplicationId(id: String): Boolean = APP_ID.matches(id)

    fun parse(text: String): ProjectConfig {
        val clean = stripComments(text)
        return ProjectConfig(
            applicationId = stringValue(clean, "applicationId"),
            versionCode = intValue(clean, "versionCode"),
            versionName = stringValue(clean, "versionName"),
            minSdk = intValue(clean, "minSdkVersion|minSdk"),
            targetSdk = intValue(clean, "targetSdkVersion|targetSdk"),
            compileSdk = intValue(clean, "compileSdkVersion|compileSdk"),
            dependencies = parseDependencies(clean)
        )
    }

    fun validate(config: ProjectConfig): List<String> {
        val errors = mutableListOf<String>()
        val id = config.applicationId
        if (id == null) {
            errors += "applicationId belum diisi"
        } else if (!isValidApplicationId(id)) {
            errors += "applicationId tidak valid: $id"
        }
        if (config.minSdk == null) errors += "minSdk belum diisi"
        return errors
    }

    private fun stripComments(text: String): String {
        val noBlock = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
    }

    private fun stringValue(text: String, keys: String): String? =
        Regex("\\b(?:$keys)\\s*=?\\s*\\(?\\s*['\"]([^'\"]+)['\"]").find(text)?.groupValues?.get(1)

    private fun intValue(text: String, keys: String): Int? =
        Regex("\\b(?:$keys)\\s*=?\\s*\\(?\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseDependencies(text: String): List<Dependency> =
        DEPENDENCY.findAll(text).map { m ->
            Dependency(
                configuration = m.groupValues[1],
                group = m.groupValues[2],
                artifact = m.groupValues[3],
                version = m.groupValues[4].ifEmpty { null }
            )
        }.toList()
}
