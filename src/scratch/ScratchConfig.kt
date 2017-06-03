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

package scratch

import scratch.ScratchConfig.AppendType.APPEND
import scratch.ScratchConfig.AppendType.PREPEND
import scratch.ScratchConfig.DefaultScratchMeaning.TOPMOST
import java.util.*


data class ScratchConfig(
    val scratches: List<Scratch>,
    val lastOpenedScratch: Scratch?,
    val listenToClipboard: Boolean,
    val needMigration: Boolean,
    val clipboardAppendType: AppendType,
    private val newScratchAppendType: AppendType,
    val defaultScratchMeaning: DefaultScratchMeaning
) {

    enum class AppendType {
        APPEND, PREPEND
    }

    enum class DefaultScratchMeaning {
        TOPMOST, LAST_OPENED
    }

    fun with(newScratches: List<Scratch>): ScratchConfig {
        return ScratchConfig(
            newScratches, lastOpenedScratch, listenToClipboard, needMigration,
            clipboardAppendType, newScratchAppendType, defaultScratchMeaning
        )
    }

    fun add(scratch: Scratch): ScratchConfig {
        val newScratches = ArrayList<Scratch>(scratches)
        if (newScratchAppendType == APPEND) {
            newScratches.add(scratch)
        } else if (newScratchAppendType == PREPEND) {
            newScratches.add(0, scratch)
        } else {
            throw IllegalStateException()
        }
        return this.with(newScratches)
    }

    fun without(scratch: Scratch): ScratchConfig {
        val newScratches = ArrayList<Scratch>(scratches)
        newScratches.remove(scratch)
        return this.with(newScratches)
    }

    fun replace(scratch: Scratch, newScratch: Scratch): ScratchConfig {
        val scratchList = scratches.map { it -> if (it == scratch) newScratch else it }
        val lastOpened = (if (scratch == lastOpenedScratch) newScratch else lastOpenedScratch)
        return ScratchConfig(scratchList, lastOpened, listenToClipboard, needMigration, clipboardAppendType, newScratchAppendType, defaultScratchMeaning)
    }

    fun move(scratch: Scratch, shift: Int): ScratchConfig {
        val oldIndex = scratches.indexOf(scratch)
        var newIndex = oldIndex + shift
        if (newIndex < 0) newIndex += scratches.size
        if (newIndex >= scratches.size) newIndex -= scratches.size

        val newScratches = ArrayList<Scratch>(scratches)
        newScratches.removeAt(oldIndex)
        newScratches.add(newIndex, scratch)
        return this.with(newScratches)
    }

    fun listenToClipboard(value: Boolean): ScratchConfig {
        return ScratchConfig(
            scratches, lastOpenedScratch, value, needMigration,
            clipboardAppendType, newScratchAppendType, defaultScratchMeaning)
    }

    fun needsMigration(value: Boolean): ScratchConfig {
        return ScratchConfig(
            scratches, lastOpenedScratch, listenToClipboard, value,
            clipboardAppendType, newScratchAppendType, defaultScratchMeaning)
    }

    fun withClipboard(value: AppendType?): ScratchConfig {
        if (value == null) return this
        return ScratchConfig(
            scratches, lastOpenedScratch, listenToClipboard, needMigration,
            value, newScratchAppendType, defaultScratchMeaning)
    }

    fun withNewScratch(value: AppendType?): ScratchConfig {
        if (value == null) return this
        return ScratchConfig(
            scratches, lastOpenedScratch, listenToClipboard, needMigration,
            clipboardAppendType, value, defaultScratchMeaning)
    }

    fun withDefaultScratchMeaning(value: DefaultScratchMeaning?): ScratchConfig {
        if (value == null) return this
        return ScratchConfig(
            scratches, lastOpenedScratch, listenToClipboard, needMigration,
            clipboardAppendType, newScratchAppendType, value)
    }

    fun withLastOpenedScratch(value: Scratch?): ScratchConfig {
        return ScratchConfig(
            scratches, value, listenToClipboard, needMigration,
            clipboardAppendType, newScratchAppendType, defaultScratchMeaning)
    }

    companion object {
        val DEFAULT_CONFIG = ScratchConfig(
            emptyList<Scratch>(), null, false, true, APPEND, APPEND, TOPMOST
        )
        val UP = -1
        val DOWN = 1
    }
}
