package mingovvv.endpointlens.idea.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class OpenHttpPathsPopupAction : AnAction("Open HTTP Paths"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HttpPathsPopupDialog(project).show()
    }

    override fun update(e: AnActionEvent) {
        // Keep update lightweight to reduce toolbar/menu update overhead.
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
