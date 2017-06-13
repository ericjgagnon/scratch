/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scratch.ide

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.UIUtil
import scratch.MrScratchManager
import scratch.Scratch
import scratch.ScratchConfig
import scratch.ScratchConfig.AppendType.APPEND
import scratch.ScratchConfig.AppendType.PREPEND
import scratch.filesystem.FileSystem
import scratch.ide.ScratchComponent.Companion.mrScratchManager
import scratch.ide.Util.takeProjectFrom
import scratch.ide.popup.ScratchListPopup
import scratch.ide.popup.ScratchListPopup.ScratchNameValidator
import scratch.ide.popup.ScratchListPopupStep
import java.awt.datatransfer.DataFlavor.stringFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.util.*
import javax.swing.Icon


class Ide(
    private val fileSystem: FileSystem,
    private val log: ScratchLog,
    private val application: Application = ApplicationManager.getApplication(),
    private val documentManager: FileDocumentManager = FileDocumentManager.getInstance()
) {
    private val noIcon: Icon? = null
    private var scratchListSelectedIndex: Int = 0

    fun persistConfig(config: ScratchConfig) {
        ScratchConfigPersistence.instance.updateFrom(config)
    }

    fun displayScratchesListPopup(scratches: List<Scratch>, userDataHolder: UserDataHolder) {
        val popupStep = ScratchListPopupStep(scratches, takeProjectFrom(userDataHolder)!!)
        popupStep.defaultOptionIndex = scratchListSelectedIndex

        val popup = object: ScratchListPopup(popupStep) {
            override fun onNewScratch() {
                application.invokeAndWait(
                    { mrScratchManager().userWantsToEnterNewScratchName(userDataHolder) },
                    ModalityState.current()
                )
            }

            override fun onRenameScratch(scratch: Scratch) {
                application.invokeAndWait(
                    { mrScratchManager().userWantsToEditScratchName(scratch) },
                    ModalityState.current()
                )
            }

            override fun onScratchDelete(scratch: Scratch) = mrScratchManager().userAttemptedToDeleteScratch(scratch, userDataHolder)

            override fun onScratchDeleteWithoutPrompt(scratch: Scratch) = mrScratchManager().userWantsToDeleteScratch(scratch)

            override fun onScratchMoved(scratch: Scratch, shift: Int) = mrScratchManager().userMovedScratch(scratch, shift)

            override fun dispose() {
                scratchListSelectedIndex = selectedIndex
                super.dispose()
            }
        }
        popup.showCenteredInCurrentWindow(takeProjectFrom(userDataHolder)!!)
    }

    fun openScratch(scratch: Scratch, userDataHolder: UserDataHolder) {
        val project = takeProjectFrom(userDataHolder)

        val file = fileSystem.virtualFileBy(scratch.fileName)
        if (file != null) {
            OpenFileDescriptor(project!!, file).navigate(true)
        } else {
            log.failedToFindVirtualFileFor(scratch)
        }
    }

    fun openNewScratchDialog(suggestedScratchName: String, userDataHolder: UserDataHolder) {
        val message = "Scratch name (you can use '&' for mnemonics):"
        val scratchName = Messages.showInputDialog(message, "New Scratch", noIcon, suggestedScratchName, object: InputValidatorEx {
            override fun checkInput(scratchName: String) = ScratchComponent.mrScratchManager().checkIfUserCanCreateScratchWithName(scratchName).isYes
            override fun getErrorText(scratchName: String) = ScratchComponent.mrScratchManager().checkIfUserCanCreateScratchWithName(scratchName).explanation
            override fun canClose(inputString: String) = true
        }) ?: return

        mrScratchManager().userWantsToAddNewScratch(scratchName, userDataHolder)
    }

    fun addTextTo(scratch: Scratch, clipboardText: String, appendType: ScratchConfig.AppendType) {
        val virtualFile = fileSystem.virtualFileBy(scratch.fileName)
        if (virtualFile == null) {
            log.failedToFindVirtualFileFor(scratch)
            return
        }
        val document = documentManager.getDocument(virtualFile) ?: return
        if (hasFocusInEditor(document)) return

        when (appendType) {
            APPEND -> {
                val text = document.charsSequence
                if (text.last() != '\n') document.insertString(text.length, "\n")
                document.insertString(text.length, clipboardText)
            }
            PREPEND -> document.insertString(0, clipboardText + "\n")
        }
        documentManager.saveDocument(document)
    }

    private fun hasFocusInEditor(document: Document): Boolean {
        fun selectedEditor(): Editor? {
            val frame = IdeFocusManager.findInstance().lastFocusedFrame ?: return null
            val project = frame.project ?: return null
            return FileEditorManager.getInstance(project).selectedTextEditor
        }
        val selectedTextEditor = selectedEditor() ?: return false
        return selectedTextEditor.document == document
    }

    fun showRenameDialogFor(scratch: Scratch) {
        val initialValue = scratch.fullNameWithMnemonics
        val message = "Scratch name (you can use '&' for mnemonics):"
        val newScratchName = Messages.showInputDialog(message, "Scratch Rename", noIcon, initialValue, ScratchNameValidator(scratch))

        if (newScratchName != null) {
            mrScratchManager().userWantsToRename(scratch, newScratchName)
        }
    }

    fun showDeleteDialogFor(scratch: Scratch, userDataHolder: UserDataHolder) {
        // Message dialog is displayed inside invokeLater() because otherwise on OSX
        // "delete" event will be propagated to editor and will remove a character.
        application.invokeLater {
            val message = "Do you want to delete '" + scratch.fileName + "'?\n(This operation cannot be undone)"
            val userAnswer = Messages.showOkCancelDialog(takeProjectFrom(userDataHolder), message, "Delete Scratch", "&Delete", "&Cancel", UIUtil.getQuestionIcon())
            if (userAnswer == Messages.OK) {
                mrScratchManager().userWantsToDeleteScratch(scratch)
            }
        }
    }


    /**
     * Note that it's possible to turn on clipboard listener and forget about it.
     * So it will keep appending clipboard content to default scratch forever.
     * To avoid the problem remind user on IDE startup that clipboard listener is on.
     */
    class ClipboardListener(
        private val mrScratchManager: MrScratchManager,
        private val copyPasteManager: CopyPasteManager = CopyPasteManager.getInstance(),
        private val application: Application = ApplicationManager.getApplication(),
        private val commandProcessor: CommandProcessor = CommandProcessor.getInstance()
    ) {
        fun startListening() {
            copyPasteManager.addContentChangedListener { oldTransferable, newTransferable ->
                if (mrScratchManager.shouldListenToClipboard()) {
                    try {
                        val oldClipboard = oldTransferable?.getTransferData(stringFlavor)?.toString()
                        val clipboard = newTransferable?.getTransferData(stringFlavor)?.toString()

                        if (clipboard != null && oldClipboard != clipboard) {
                            // Invoke action later so that modification of document is not tracked by IDE as undoable "Copy" action
                            // See https://github.com/dkandalov/scratch/issues/30
                            application.invokeLater {
                                application.runWriteAction {
                                    commandProcessor.executeCommand(null, {
                                        mrScratchManager.clipboardListenerWantsToAddTextToScratch(clipboard)
                                    }, null, null, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
                                }
                            }
                        }
                    } catch (e: UnsupportedFlavorException) {
                        log.info(e)
                    } catch (e: IOException) {
                        log.info(e)
                    }
                }
            }
        }

        companion object {
            private val log = Logger.getInstance(ClipboardListener::class.java)
        }
    }

    class OpenEditorTracker(
        private val mrScratchManager: MrScratchManager,
        private val fileSystem: FileSystem,
        private val application: Application = ApplicationManager.getApplication()
    ) {
        // use WeakHashMap "just in case" to avoid keeping project references
        private val connectionsByProject = WeakHashMap<Project, MessageBusConnection>()

        fun startTracking(): OpenEditorTracker {
            application.messageBus.connect().subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectOpened(project: Project) {
                    val connection = project.messageBus.connect()
                    connection.subscribe(FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                        override fun selectionChanged(event: FileEditorManagerEvent) {
                            val virtualFile = event.newFile ?: return
                            if (fileSystem.isScratch(virtualFile)) {
                                mrScratchManager.userOpenedScratch(virtualFile.name)
                            }
                        }
                    })
                    connectionsByProject.put(project, connection)
                }

                override fun projectClosed(project: Project) {
                    connectionsByProject.remove(project)?.disconnect()
                }
            })
            return this
        }
    }
}
