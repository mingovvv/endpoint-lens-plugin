package mingovvv.endpointlens.idea.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager

class EndpointStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val connection = project.messageBus.connect()
        connection.subscribe(VirtualFileManager.VFS_CHANGES, EndpointVfsChangeListener(project))
        EndpointProjectIndexService.getInstance(project).scheduleFullReindex()
    }
}

