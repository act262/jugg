package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Toolchain cache directories that never hold user-editable external sources. */
private val externalBuildCacheDirectoryNames = setOf(".dart_tool", ".cxx", ".externalNativeBuild")

/** One module-specific external build matched by a changed physical source file. */
data class ExternalBuildTarget(
    val module: ModuleInfo,
    val buildInfo: ExternalBuildInfo,
)

/** Whether the file lives in a toolchain cache directory such as `.dart_tool`, `.cxx` or `.externalNativeBuild`. */
fun File.isInExternalBuildCacheDirectory(): Boolean {
    return toPath().toAbsolutePath().normalize().map { it.toString() }
        .any { it in externalBuildCacheDirectoryNames }
}

/**
 * Resolves the first external build matched by one module for legacy single-module callers.
 */
fun resolveExternalBuild(module: ModuleInfo, file: File): ExternalBuildInfo? {
    return resolveExternalBuilds(listOf(module), file).firstOrNull()?.buildInfo
}

/**
 * Resolves every module-specific external build matched by one physical source file. Configuration
 * inputs match exactly, while input directories deliberately match every descendant. False
 * positives are acceptable because Gradle remains the authority for task up-to-date checks.
 */
fun resolveExternalBuilds(modules: Collection<ModuleInfo>, file: File): List<ExternalBuildTarget> {
    if (file.isInExternalBuildCacheDirectory()) {
        return emptyList()
    }
    val path = file.toPath().toAbsolutePath().normalize()
    return modules.flatMap { module ->
        module.externalBuildInfos.mapNotNull { buildInfo ->
            if (buildInfo.excludedDirs.any { path.startsWith(it.toPath().toAbsolutePath().normalize()) }) {
                return@mapNotNull null
            }
            val isConfigInput = buildInfo.configFiles.any { path == it.toPath().toAbsolutePath().normalize() }
            val isUnderInputDir = buildInfo.inputDirs.any {
                path.startsWith(it.toPath().toAbsolutePath().normalize())
            }
            if (isConfigInput || isUnderInputDir) {
                ExternalBuildTarget(module, buildInfo)
            } else {
                null
            }
        }
    }
}
