/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for IntelliJ IDEA.
 * Copyright (C) 2019-2026 EPAM Systems <hybrisideaplugin@epam.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package sap.commerce.toolset.java.jarFinder

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Outcome of the previous library roots lookup, stored aside the sources and javadocs jars it describes.
 *
 * It is a discardable cache and not a configuration: machine local, shared by all projects on the machine and wiped
 * together with the library sources directory, the very same way a developer already forces a re-fetch.
 */
class LibraryRootLookupCache(
    private val librarySourceDir: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val artifactCoords = ConcurrentHashMap<String, CachedArtifactCoords>()
    private val absentRootTypes = ConcurrentHashMap<String, MutableSet<String>>()
    private val absenceRecordedAt = ConcurrentHashMap<String, Long>()
    private val dirty = AtomicBoolean(false)
    private var repositoriesFingerprint = 0

    /**
     * Identity of a library jar, the size is taken into account to invalidate the outcome once the jar is replaced.
     */
    fun artifactKey(name: String, length: Long) = "$name|$length"

    fun isKnownAbsent(artifactKey: String, libraryRootType: LibraryRootType) = absentRootTypes[artifactKey]
        ?.contains(libraryRootType.name) == true

    fun rememberAbsent(artifactKey: String, libraryRootType: LibraryRootType) {
        absentRootTypes.computeIfAbsent(artifactKey) { ConcurrentHashMap.newKeySet() }
            .add(libraryRootType.name)
        // an absence established during a previous lookup keeps its own age, otherwise it would never expire
        absenceRecordedAt.putIfAbsent(artifactKey, now())

        dirty.set(true)
    }

    fun knownArtifactCoords(artifactKey: String) = artifactCoords[artifactKey]
        ?.let { MavenArtifactCoords(it.groupId, it.artifactId, it.version, "cache") }

    fun rememberArtifactCoords(artifactKey: String, mavenArtifactCoords: MavenArtifactCoords) {
        artifactCoords[artifactKey] = with(mavenArtifactCoords) { CachedArtifactCoords(groupId, artifactId, version) }

        dirty.set(true)
    }

    /**
     * A recorded absence is only meaningful for the repositories it was established against and only for a limited
     * period of time, an artifact may be published to a repository at any moment.
     *
     * Resolved coordinates, on the contrary, are an immutable property of the very same library jar.
     */
    fun load(lookupRepositories: Collection<String>) {
        artifactCoords.clear()
        absentRootTypes.clear()
        absenceRecordedAt.clear()
        repositoriesFingerprint = fingerprintOf(lookupRepositories)
        dirty.set(false)

        val cached = read() ?: return

        artifactCoords.putAll(cached.artifactCoords)

        if (cached.repositoriesFingerprint != repositoriesFingerprint) return

        cached.absentRootTypes
            .filterValues { now() - it.recordedAt <= ABSENCE_LIFESPAN }
            .forEach { (artifactKey, absence) ->
                absentRootTypes[artifactKey] = ConcurrentHashMap.newKeySet<String>()
                    .apply { addAll(absence.rootTypes) }
                absenceRecordedAt[artifactKey] = absence.recordedAt
            }
    }

    fun flush() {
        if (!dirty.getAndSet(false)) return

        val cacheFile = cacheFile() ?: return
        val model = LibraryRootLookupCacheModel(
            repositoriesFingerprint = repositoriesFingerprint,
            artifactCoords = artifactCoords.toMap(),
            absentRootTypes = absentRootTypes.mapValues { (artifactKey, rootTypes) -> CachedAbsence(rootTypes.toSet(), absenceRecordedAt[artifactKey] ?: now()) },
        )

        // written aside and moved, a concurrently refreshed project must never observe a half written cache
        runCatching {
            val tmp = Files.createTempFile(cacheFile.parent, CACHE_FILE_NAME, ".tmp")

            try {
                tmp.writeText(JSON.encodeToString(model))
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                tmp.deleteIfExists()
                throw e
            }
        }
            .onFailure { thisLogger().debug("Unable to write the library roots lookup cache: $cacheFile, due: ${it.message}") }
    }

    private fun read(): LibraryRootLookupCacheModel? {
        val cacheFile = cacheFile()
            ?.takeIf { Files.isRegularFile(it) }
            ?: return null

        return runCatching { JSON.decodeFromString<LibraryRootLookupCacheModel>(cacheFile.readText()) }
            .onFailure {
                thisLogger().debug("Unable to read the library roots lookup cache: $cacheFile, due: ${it.message}")

                runCatching { cacheFile.deleteIfExists() }
            }
            .getOrNull()
    }

    private fun cacheFile() = librarySourceDir
        .takeIf { Files.isDirectory(it) }
        ?.resolve(CACHE_FILE_NAME)

    private fun fingerprintOf(lookupRepositories: Collection<String>) = lookupRepositories.toSortedSet().hashCode()

    companion object {
        private const val CACHE_FILE_NAME = "library-roots-lookup.json"
        private val ABSENCE_LIFESPAN = TimeUnit.DAYS.toMillis(30)
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class LibraryRootLookupCacheModel(
    val repositoriesFingerprint: Int = 0,
    val artifactCoords: Map<String, CachedArtifactCoords> = emptyMap(),
    val absentRootTypes: Map<String, CachedAbsence> = emptyMap(),
)

@Serializable
data class CachedArtifactCoords(
    val groupId: String,
    val artifactId: String,
    val version: String,
)

@Serializable
data class CachedAbsence(
    val rootTypes: Set<String> = emptySet(),
    val recordedAt: Long = 0,
)
