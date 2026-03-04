package mingovvv.endpointlens.idea.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import mingovvv.endpointlens.core.index.DuplicateEndpointDetector
import mingovvv.endpointlens.idea.index.EndpointIndexListener
import mingovvv.endpointlens.idea.index.EndpointProjectIndexService
import mingovvv.endpointlens.idea.index.IndexedEndpoint
import mingovvv.endpointlens.idea.search.EndpointSearchQuery
import mingovvv.endpointlens.idea.search.EndpointSearchService

class HttpPathsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val searchField = JBTextField()
    private val methodFilter = JComboBox<String>()
    private val moduleFilter = JComboBox<String>()
    private val controllerFilter = JComboBox<String>()
    private val listModel = DefaultListModel<IndexedEndpoint>()
    private val resultList = JBList(listModel)
    private val statusLabel = JBLabel("Indexing...")

    private val searchService = EndpointSearchService(project)
    private val indexService = EndpointProjectIndexService.getInstance(project)
    private var busConnection = project.messageBus.connect()
    private var disconnected = false
    private var suppressRefresh = false
    private var highlightTokens: List<String> = emptyList()

    init {
        border = JBUI.Borders.empty(8)
        add(buildFiltersPanel(), BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        setupList()
        refreshFilters()
        refreshResults()
        bindEvents()
        subscribeIndexUpdate()
    }

    override fun removeNotify() {
        if (!disconnected) {
            busConnection.disconnect()
            disconnected = true
        }
        super.removeNotify()
    }

    private fun buildFiltersPanel(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        val top = JPanel(BorderLayout())
        top.add(JBLabel("HTTP Endpoint Search"), BorderLayout.WEST)
        top.add(searchField, BorderLayout.CENTER)
        searchField.toolTipText = "example: GET /users, /orders/{id}, users"

        val filters = JPanel(BorderLayout(JBUI.scale(8), 0))
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        left.add(JBLabel("Method"))
        left.add(methodFilter)
        left.add(JBLabel("Module"))
        left.add(moduleFilter)

        val controllerPanel = JPanel(BorderLayout(JBUI.scale(6), 0))
        controllerPanel.add(JBLabel("Controller"), BorderLayout.WEST)
        controllerPanel.add(controllerFilter, BorderLayout.CENTER)

        methodFilter.preferredSize = JBUI.size(95, methodFilter.preferredSize.height)
        moduleFilter.preferredSize = JBUI.size(140, moduleFilter.preferredSize.height)

        filters.add(left, BorderLayout.WEST)
        filters.add(controllerPanel, BorderLayout.CENTER)

        panel.add(top, BorderLayout.NORTH)
        panel.add(filters, BorderLayout.SOUTH)
        return panel
    }

    private fun setupList() {
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.emptyText.text = "No endpoints found"
        resultList.fixedCellHeight = JBUI.scale(46)
        resultList.cellRenderer = EndpointCellRenderer()
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) navigateToSelected()
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) showPopup(e)
            }
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
        })
    }

    private fun bindEvents() {
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { refreshResults() }
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { moveSelection(1); e.consume() }
                    KeyEvent.VK_UP   -> { moveSelection(-1); e.consume() }
                    KeyEvent.VK_ENTER -> { navigateToSelectedOrFirst(); e.consume() }
                }
            }
        })
        methodFilter.addActionListener { if (!suppressRefresh) refreshResults() }
        moduleFilter.addActionListener { if (!suppressRefresh) refreshResults() }
        controllerFilter.addActionListener { if (!suppressRefresh) refreshResults() }
        resultList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) { navigateToSelectedOrFirst(); e.consume() }
            }
        })
    }

    private fun subscribeIndexUpdate() {
        if (disconnected) {
            busConnection = project.messageBus.connect()
            disconnected = false
        }
        busConnection.subscribe(EndpointIndexListener.TOPIC, object : EndpointIndexListener {
            override fun indexUpdated() {
                ApplicationManager.getApplication().invokeLater {
                    refreshFilters()
                    refreshResults()
                }
            }
        })
    }

    private fun refreshFilters() {
        suppressRefresh = true
        try {
            val selectedMethod = methodFilter.selectedItem?.toString() ?: "ALL"
            val selectedModule = moduleFilter.selectedItem?.toString() ?: "ALL"
            val selectedController = controllerFilter.selectedItem?.toString() ?: "ALL"

            val methodItems = searchService.methods()
            val moduleItems = searchService.modules()
            val controllerItems = searchService.controllers()

            methodFilter.model = DefaultComboBoxModel(methodItems.toTypedArray())
            moduleFilter.model = DefaultComboBoxModel(moduleItems.toTypedArray())
            controllerFilter.model = DefaultComboBoxModel(controllerItems.toTypedArray())

            methodFilter.selectedItem = if (methodItems.contains(selectedMethod)) selectedMethod else "ALL"
            moduleFilter.selectedItem = if (moduleItems.contains(selectedModule)) selectedModule else "ALL"
            controllerFilter.selectedItem = if (controllerItems.contains(selectedController)) selectedController else "ALL"
        } finally {
            suppressRefresh = false
        }
    }

    private fun refreshResults() {
        val previous = resultList.selectedValue
        listModel.clear()
        val selectedMethod = methodFilter.selectedItem?.toString() ?: "ALL"
        val selectedModule = moduleFilter.selectedItem?.toString() ?: "ALL"
        val selectedController = controllerFilter.selectedItem?.toString() ?: "ALL"

        val results = searchService.search(
            rawQuery = searchField.text.orEmpty(),
            methodFilter = selectedMethod,
            moduleFilter = selectedModule,
            controllerFilter = selectedController
        )
        highlightTokens = currentHighlightTokens()
        results.forEach { listModel.addElement(it) }
        if (previous != null) {
            val idx = (0 until listModel.size).firstOrNull {
                listModel.getElementAt(it).endpoint.sourceFile == previous.endpoint.sourceFile &&
                    listModel.getElementAt(it).endpoint.line == previous.endpoint.line
            }
            if (idx != null) resultList.selectedIndex = idx
        }
        val total = indexService.getAll().size
        val lastError = indexService.getLastError()
        statusLabel.text = when {
            indexService.isIndexing() -> "Indexing... ${results.size} shown"
            !lastError.isNullOrBlank() -> "Index failed: $lastError"
            else -> "Indexed $total endpoints | Showing ${results.size}"
        }
        if (resultList.selectedIndex < 0 && listModel.size > 0) {
            resultList.selectedIndex = 0
        }
    }

    private fun navigateToSelected() {
        val selected = resultList.selectedValue ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(selected.endpoint.sourceFile) ?: return
        OpenFileDescriptor(project, file, (selected.endpoint.line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun navigateToSelectedOrFirst() {
        if (listModel.isEmpty) return
        if (resultList.selectedIndex < 0) resultList.selectedIndex = 0
        navigateToSelected()
    }

    private fun moveSelection(delta: Int) {
        if (listModel.isEmpty) return
        val next = ((if (resultList.selectedIndex < 0) 0 else resultList.selectedIndex) + delta)
            .coerceIn(0, listModel.size - 1)
        resultList.selectedIndex = next
        resultList.ensureIndexIsVisible(next)
    }

    private fun showPopup(event: MouseEvent) {
        val selected = resultList.selectedValue ?: return
        val popup = JPopupMenu()

        val copyEndpoint = JMenuItem("Copy endpoint")
        copyEndpoint.addActionListener {
            copyToClipboard("${selected.endpoint.httpMethod} ${selected.endpoint.fullPath}")
        }
        popup.add(copyEndpoint)

        val copyCurl = JMenuItem("Copy curl template")
        copyCurl.addActionListener {
            copyToClipboard("curl -X ${selected.endpoint.httpMethod} \"http://localhost:8080${selected.endpoint.fullPath}\"")
        }
        popup.add(copyCurl)

        val showDuplicates = JMenuItem("Show duplicates")
        showDuplicates.addActionListener {
            val key = DuplicateEndpointDetector.keyOf(selected.endpoint)
            val duplicates = indexService.getDuplicatesByKey()[key].orEmpty()
            val message = if (duplicates.isEmpty()) {
                "No duplicates for $key"
            } else {
                duplicates.joinToString("\n") { "- ${it.controllerFqn}#${it.methodName} (${it.sourceFile}:${it.line})" }
            }
            Messages.showInfoMessage(project, message, "Duplicate Endpoints")
        }
        popup.add(showDuplicates)

        popup.show(resultList, event.x, event.y)
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun displaySource(sourceFile: String, line: Int): String {
        val normalized = sourceFile.replace('\\', '/')
        val srcIdx = normalized.indexOf("/src/")
        val compact = if (srcIdx >= 0) normalized.substring(srcIdx + 1) else normalized.substringAfterLast('/')
        return "$compact:$line"
    }

    fun preferredFocusComponent() = searchField

    // Keep highlight rendering HTML-free to avoid BasicHTML freeze regressions.
    private fun currentHighlightTokens(): List<String> {
        val query = EndpointSearchQuery.parse(searchField.text.orEmpty())
        return buildList {
            query.pathToken?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            query.textTokens.forEach { token ->
                token.trim().takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            }
        }.distinct().sortedByDescending { it.length }
    }

    private fun appendHighlighted(
        component: SimpleColoredComponent,
        text: String,
        tokens: List<String>,
        normal: SimpleTextAttributes,
        highlight: SimpleTextAttributes
    ) {
        val validTokens = tokens.filter { it.length >= 2 }.distinct()
        if (validTokens.isEmpty()) {
            component.append(text, normal)
            return
        }

        val lowered = text.lowercase()
        var cursor = 0
        while (cursor < text.length) {
            var bestStart = -1
            var bestLen = 0
            for (token in validTokens) {
                val start = lowered.indexOf(token, cursor)
                if (start < 0) continue
                if (bestStart == -1 || start < bestStart || (start == bestStart && token.length > bestLen)) {
                    bestStart = start
                    bestLen = token.length
                }
            }

            if (bestStart < 0) {
                component.append(text.substring(cursor), normal)
                return
            }
            if (bestStart > cursor) {
                component.append(text.substring(cursor, bestStart), normal)
            }
            component.append(text.substring(bestStart, bestStart + bestLen), highlight)
            cursor = bestStart + bestLen
        }
    }

    private inner class EndpointCellRenderer : ListCellRenderer<IndexedEndpoint> {
        private val outerPanel = JPanel(BorderLayout())
        private val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        private val methodLabel = JLabel()
        private val pathText = SimpleColoredComponent()
        private val locationText = SimpleColoredComponent()
        private val boldFont: Font
        private val smallFont: Font

        init {
            val base = methodLabel.font
            boldFont = base.deriveFont(Font.BOLD)
            smallFont = base.deriveFont(base.size2D - 1f)

            methodLabel.font = boldFont
            pathText.font = base
            locationText.font = smallFont

            topPanel.isOpaque = false
            topPanel.add(methodLabel)
            topPanel.add(pathText)

            outerPanel.add(topPanel, BorderLayout.CENTER)
            outerPanel.add(locationText, BorderLayout.SOUTH)
            outerPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 8)
            )
        }

        override fun getListCellRendererComponent(
            list: JList<out IndexedEndpoint>,
            value: IndexedEndpoint?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) {
                methodLabel.text = ""
                pathText.clear()
                locationText.clear()
                return outerPanel
            }

            val methodRaw = value.endpoint.httpMethod.uppercase()
            methodLabel.text = methodRaw
            methodLabel.foreground = if (isSelected) list.selectionForeground else methodColor(methodRaw)

            val pathColor = if (isSelected) list.selectionForeground else list.foreground
            val locationColor = if (isSelected) list.selectionForeground else JBColor.GRAY
            val normalPath = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, pathColor)
            val normalLocation = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, locationColor)
            val highlightPath = if (isSelected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, pathColor)
            } else {
                SimpleTextAttributes(JBColor(Color(255, 244, 200), Color(72, 62, 25)), pathColor, null, SimpleTextAttributes.STYLE_BOLD)
            }
            val highlightLocation = if (isSelected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, locationColor)
            } else {
                SimpleTextAttributes(JBColor(Color(255, 244, 200), Color(72, 62, 25)), locationColor, null, SimpleTextAttributes.STYLE_BOLD)
            }

            pathText.clear()
            appendHighlighted(pathText, value.endpoint.fullPath, highlightTokens, normalPath, highlightPath)

            locationText.clear()
            appendHighlighted(
                locationText,
                displaySource(value.endpoint.sourceFile, value.endpoint.line),
                highlightTokens,
                normalLocation,
                highlightLocation
            )

            val bg = if (isSelected) list.selectionBackground else list.background
            outerPanel.background = bg
            topPanel.background = bg
            outerPanel.isOpaque = true
            topPanel.isOpaque = true
            pathText.isOpaque = false
            locationText.isOpaque = false

            return outerPanel
        }

        private fun methodColor(method: String): Color = when (method) {
            "GET"    -> JBColor(Color(0x2E7D32), Color(0x81C784))
            "POST"   -> JBColor(Color(0x1565C0), Color(0x90CAF9))
            "PUT"    -> JBColor(Color(0x6A1B9A), Color(0xCE93D8))
            "PATCH"  -> JBColor(Color(0xEF6C00), Color(0xFFCC80))
            "DELETE" -> JBColor(Color(0xC62828), Color(0xEF9A9A))
            else     -> JBColor(Color(0x546E7A), Color(0xB0BEC5))
        }
    }
}
