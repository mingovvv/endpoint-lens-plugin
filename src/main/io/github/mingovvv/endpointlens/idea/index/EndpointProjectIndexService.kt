package mingovvv.endpointlens.idea.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import mingovvv.endpointlens.core.index.DuplicateEndpointDetector
import mingovvv.endpointlens.core.model.EndpointDuplicate
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.idea.extractor.SpringMappingExtractor

@Service(Service.Level.PROJECT)
class EndpointProjectIndexService(private val project: Project) {
    private val log = logger<EndpointProjectIndexService>()
    private val extractor = SpringMappingExtractor()
    private val byFilePath = ConcurrentHashMap<String, List<IndexedEndpoint>>()
    private val indexing = AtomicBoolean(false)
    @Volatile private var lastError: String? = null
    @Volatile private var pendingPublishFuture: ScheduledFuture<*>? = null

    fun scheduleFullReindex() {
        if (!indexing.compareAndSet(false, true)) return
        lastError = null
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                reindexAll()
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                log.warn("Endpoint full reindex failed", t)
            } finally {
                indexing.set(false)
                publishUpdated()
            }
        }
    }

    fun scheduleRefresh(file: VirtualFile) {
        if (!isSupportedFile(file)) return
        if (indexing.get()) return // full reindex가 진행 중이면 개별 refresh 무시
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                refreshFile(file)
                lastError = null
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                log.warn("Endpoint refresh failed for ${file.path}", t)
            } finally {
                publishUpdated()
            }
        }
    }

    fun getAll(): List<IndexedEndpoint> = byFilePath.values.flatten()

    fun getDuplicates(): List<EndpointDuplicate> {
        val endpoints = getAll().map { it.endpoint }
        return DuplicateEndpointDetector.detect(endpoints)
    }

    fun getDuplicatesByKey(): Map<String, List<HttpEndpoint>> {
        return getDuplicates().associate { it.key to it.endpoints }
    }

    fun isIndexing(): Boolean = indexing.get()
    fun getLastError(): String? = lastError

    private fun reindexAll() {
        val supportedFiles = collectSupportedFiles()
        if (supportedFiles.isEmpty()) {
            byFilePath.clear()
            lastError = "No Kotlin/Java files found in project scope"
            return
        }

        val next = mutableMapOf<String, List<IndexedEndpoint>>()
        for (file in supportedFiles) {
            val records = extractFromFile(file)
            if (records.isNotEmpty()) {
                next[file.path] = records
            }
        }
        byFilePath.clear()
        byFilePath.putAll(next)
    }

    private fun collectSupportedFiles(): List<VirtualFile> {
        val fromProjectIndex = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val result = mutableListOf<VirtualFile>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (isSupportedFile(file)) result.add(file)
                true
            }
            result
        }
        if (fromProjectIndex.isNotEmpty()) return fromProjectIndex

        // Fallback: if project model/content roots are not ready, scan from project base directory.
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val basePath = project.basePath ?: return@compute emptyList()
            val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@compute emptyList()
            val result = mutableListOf<VirtualFile>()

            VfsUtilCore.iterateChildrenRecursively(
                baseDir,
                { file ->
                    if (!file.isValid) return@iterateChildrenRecursively false
                    val normalized = file.path.replace('\\', '/')
                    !normalized.contains("/.git/") &&
                        !normalized.contains("/.gradle/") &&
                        !normalized.contains("/build/") &&
                        !normalized.contains("/out/")
                },
                { file ->
                    if (!file.isDirectory && isSupportedFile(file)) {
                        result += file
                    }
                    true
                }
            )
            result
        }
    }

    private fun refreshFile(file: VirtualFile) {
        if (!file.isValid) {
            byFilePath.remove(file.path)
            return
        }
        val records = extractFromFile(file)
        if (records.isEmpty()) {
            byFilePath.remove(file.path)
        } else {
            byFilePath[file.path] = records
            if (!lastError.isNullOrBlank() && lastError!!.startsWith("No Kotlin/Java files found")) {
                lastError = null
            }
        }
    }

    private fun extractFromFile(file: VirtualFile): List<IndexedEndpoint> {
        // ReadAction 범위 최소화: 파일 읽기 + 모듈명만 lock 안에서 수행
        val (source, moduleName) = ReadAction.compute<Pair<String, String>, RuntimeException> {
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
            val module = ProjectFileIndex.getInstance(project).getModuleForFile(file)?.name ?: "default"
            text to module
        }

        if (source.isBlank()) return emptyList()

        // 파싱은 read lock 밖에서 수행 (CPU 작업이므로 lock 불필요)
        return extractor.extractFromSource(source, file.path).map { endpoint ->
            IndexedEndpoint(endpoint = endpoint, moduleName = moduleName)
        }
    }

    private fun publishUpdated() {
        pendingPublishFuture?.cancel(false)
        pendingPublishFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            project.messageBus.syncPublisher(EndpointIndexListener.TOPIC).indexUpdated()
        }, 300, TimeUnit.MILLISECONDS)
    }

    companion object {
        fun getInstance(project: Project): EndpointProjectIndexService = project.getService(EndpointProjectIndexService::class.java)

        fun isSupportedFile(file: VirtualFile): Boolean {
            val ext = file.extension?.lowercase() ?: return false
            return ext == "kt" || ext == "java"
        }
    }
}
