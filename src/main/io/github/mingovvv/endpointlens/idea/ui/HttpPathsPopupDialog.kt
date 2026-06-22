package mingovvv.endpointlens.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent

class HttpPathsPopupDialog(project: Project) : DialogWrapper(project) {
    private val panel = HttpPathsPanel(project)

    init {
        title = "Endpoint Lens"
        setResizable(true)
        panel.onNavigate = { close(0) }
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusComponent()

    override fun createActions() = emptyArray<javax.swing.Action>()

    // Persist the user-resized window size across reopen and IDE restarts.
    override fun getDimensionServiceKey(): String = "EndpointLens.HttpPathsPopupDialog"

    // DPI-aware initial size; larger default than before.
    override fun getInitialSize(): Dimension = JBUI.size(1040, 760)
}
