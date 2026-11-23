package com.devqa.feature.projectbrain.application

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/** Project-local scan preferences. These never leave the IDE. */
@Service(Service.Level.PROJECT)
@State(name = "DevQAProjectBrainSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectBrainSettings : PersistentStateComponent<ProjectBrainSettings.Options> {
    data class Options(
        var includeTests: Boolean = true,
        var includeGenerated: Boolean = false,
        var autoRefresh: Boolean = true,
        var maxFileBytes: Long = 8L * 1024 * 1024,
        var includedModules: MutableList<String> = mutableListOf(),
        var excludedDirectories: MutableList<String> = mutableListOf()
    )

    @Volatile private var options = Options()

    override fun getState(): Options = options.copy(
        includedModules = options.includedModules.toMutableList(),
        excludedDirectories = options.excludedDirectories.toMutableList()
    )

    override fun loadState(state: Options) {
        options = state.copy(
            maxFileBytes = state.maxFileBytes.coerceIn(16 * 1024L, 128L * 1024 * 1024),
            includedModules = state.includedModules.filter { it.isNotBlank() }.distinct().toMutableList(),
            excludedDirectories = state.excludedDirectories.map { it.replace('\\', '/').trim('/') }
                .filter { it.isNotBlank() && ".." !in it }.distinct().toMutableList()
        )
    }

    fun allows(project: Project, file: VirtualFile, index: ProjectFileIndex): Boolean {
        // Read one immutable snapshot. A concurrent settings update must not mix
        // values from two configurations during the same file decision.
        val current = options
        if (!file.isValid || file.isDirectory || file.length > current.maxFileBytes) return false
        if (!current.includeTests && index.isInTestSourceContent(file)) return false
        if (!current.includeGenerated && index.isInGeneratedSources(file)) return false
        if (current.includedModules.isNotEmpty() && index.getModuleForFile(file)?.name !in current.includedModules) return false
        val root = project.basePath?.trimEnd('/') ?: return false
        val relative = file.path.removePrefix(root).trimStart('/').replace('\\', '/')
        return current.excludedDirectories.none { relative == it || relative.startsWith("$it/") }
    }
}
