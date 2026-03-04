package mingovvv.endpointlens.idea.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

class EndpointVfsChangeListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val service = EndpointProjectIndexService.getInstance(project)
        events.forEach { event ->
            val file = event.file ?: return@forEach
            if (!EndpointProjectIndexService.isSupportedFile(file)) return@forEach
            service.scheduleRefresh(file)
        }

        val structuralChange = events.any { event ->
            event is VFileMoveEvent ||
                event is VFilePropertyChangeEvent && event.propertyName == "name"
        }
        if (structuralChange) {
            service.scheduleFullReindex()
        }
    }
}

