package mingovvv.endpointlens.idea.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Persists the Endpoint Lens search popup state (query + filters) so it survives
 * reopening the popup and IDE restarts. Stored per-project.
 */
@Service(Service.Level.PROJECT)
@State(name = "EndpointLensSearchState", storages = [Storage("endpointLensSearch.xml")])
class EndpointSearchUiState : PersistentStateComponent<EndpointSearchUiState.State> {
    data class State(
        var query: String = "",
        var methodFilter: String = "ALL",
        var moduleFilter: String = "ALL",
        var controllerFilter: String = "ALL"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun update(query: String, methodFilter: String, moduleFilter: String, controllerFilter: String) {
        state = State(query, methodFilter, moduleFilter, controllerFilter)
    }

    companion object {
        fun getInstance(project: Project): EndpointSearchUiState =
            project.getService(EndpointSearchUiState::class.java)
    }
}
