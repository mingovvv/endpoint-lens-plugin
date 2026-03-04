package mingovvv.endpointlens.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import mingovvv.endpointlens.core.index.DuplicateEndpointDetector
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.idea.index.EndpointProjectIndexService

class EndpointDuplicateInspection : LocalInspectionTool() {
    override fun getID(): String = "HPS001_DUPLICATE_ENDPOINT"

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        val service = EndpointProjectIndexService.getInstance(file.project)
        val duplicatesByKey = service.getDuplicatesByKey()
        if (duplicatesByKey.isEmpty()) return emptyArray()

        val currentEndpoints = service.getAll()
            .filter { it.endpoint.sourceFile == file.virtualFile?.path }
            .map { it.endpoint }
        if (currentEndpoints.isEmpty()) return emptyArray()

        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile ?: return emptyArray()) ?: return emptyArray()
        val problems = mutableListOf<ProblemDescriptor>()
        currentEndpoints.forEach { endpoint ->
            val key = DuplicateEndpointDetector.keyOf(endpoint)
            val dupList = duplicatesByKey[key].orEmpty()
            if (dupList.size < 2) return@forEach

            val target = findLineElement(file, document, endpoint.line)
            val message = buildMessage(endpoint, dupList)
            problems += manager.createProblemDescriptor(
                target,
                message,
                false,
                ProblemHighlightType.WARNING,
                isOnTheFly
            )
        }
        return problems.toTypedArray()
    }

    private fun findLineElement(file: PsiFile, document: Document, line: Int): PsiElement {
        val safeLine = (line - 1).coerceAtLeast(0).coerceAtMost(document.lineCount - 1)
        val offset = document.getLineStartOffset(safeLine)
        return file.findElementAt(offset) ?: file
    }

    private fun buildMessage(current: HttpEndpoint, dupList: List<HttpEndpoint>): String {
        val other = dupList.firstOrNull { it.sourceFile != current.sourceFile || it.line != current.line } ?: dupList.first()
        return "Duplicate endpoint: ${current.httpMethod} ${current.fullPath}. Also defined at ${other.sourceFile}:${other.line}"
    }
}

