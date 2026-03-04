package mingovvv.endpointlens.idea.index

import com.intellij.util.messages.Topic

interface EndpointIndexListener {
    fun indexUpdated()

    companion object {
        val TOPIC: Topic<EndpointIndexListener> =
            Topic.create("Endpoint Lens Index Updated", EndpointIndexListener::class.java)
    }
}

