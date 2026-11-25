package com.androidharness.app.androiduse

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AutoJsScript(val name: String, val source: String, val enabled: Boolean = true)

/** Private, atomic script catalog. Disabling an entry keeps its source available for editing. */
class AutoJsScriptStore(private val file: File) {
    private val serial = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val catalog = MutableStateFlow(if (file.exists()) json.decodeFromString<List<AutoJsScript>>(file.readText()) else emptyList())
    val entries = catalog.asStateFlow()
    fun get(name: String) = catalog.value.singleOrNull { it.name == name }

    suspend fun save(name: String, source: String, enabled: Boolean = true) {
        require(name.length in 1..60 && name == name.trim() && name.none(Char::isISOControl)) {
            "Use a name of 1–60 visible characters."
        }
        require(source.length <= AutoJsUiScripts.MAX_CHARS) { "Script is too long." }
        if (enabled) AutoJsUiScripts.check(source)
        change { current ->
            require(current.size < 100 || current.any { it.name == name }) { "This library already has 100 scripts." }
            current.filterNot { it.name == name } + AutoJsScript(name, source, enabled)
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = change { current ->
        val item = current.singleOrNull { it.name == name } ?: error("Script not found.")
        if (enabled) AutoJsUiScripts.check(item.source)
        current.map { if (it.name == name) item.copy(enabled = enabled) else it }
    }

    suspend fun delete(name: String) = change { current ->
        require(current.any { it.name == name }) { "Script not found." }
        current.filterNot { it.name == name }
    }

    private suspend fun change(transform: (List<AutoJsScript>) -> List<AutoJsScript>) = withContext(Dispatchers.IO) {
        serial.withLock {
            val updated = transform(catalog.value).sortedBy { it.name }
            val parent = requireNotNull(file.parentFile)
            Files.createDirectories(parent.toPath())
            val temp = Files.createTempFile(parent.toPath(), ".ui-scripts-", ".tmp")
            try {
                Files.write(temp, json.encodeToString(updated).toByteArray(Charsets.UTF_8))
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
            catalog.value = updated
        }
    }
}
