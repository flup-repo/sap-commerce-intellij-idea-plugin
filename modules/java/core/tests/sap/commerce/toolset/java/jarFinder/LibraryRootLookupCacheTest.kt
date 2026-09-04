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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRootLookupCacheTest {

    private lateinit var librarySourceDir: Path

    @BeforeTest
    fun createLibrarySourceDir() {
        librarySourceDir = Files.createTempDirectory("library-roots-lookup")
    }

    @AfterTest
    fun removeLibrarySourceDir() {
        librarySourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `nothing is known about a library jar which was never looked up`() {
        val cache = cache()

        val artifactKey = cache.artifactKey("guava-33.0.0.jar", 3_048_576)

        assertFalse(cache.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
        assertNull(cache.knownArtifactCoords(artifactKey))
    }

    @Test
    fun `absent library root type is remembered for the very same library jar`() {
        val cache = cache()
        val artifactKey = cache.artifactKey("guava-33.0.0.jar", 3_048_576)

        cache.rememberAbsent(artifactKey, LibraryRootType.SOURCES)

        assertTrue(cache.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is not reported for another library root type`() {
        val cache = cache()
        val artifactKey = cache.artifactKey("guava-33.0.0.jar", 3_048_576)

        cache.rememberAbsent(artifactKey, LibraryRootType.SOURCES)

        assertFalse(cache.isKnownAbsent(artifactKey, LibraryRootType.JAVADOC))
    }

    @Test
    fun `absent library root types are remembered independently per library jar`() {
        val cache = cache()
        val guava = cache.artifactKey("guava-33.0.0.jar", 3_048_576)
        val gson = cache.artifactKey("gson-2.11.0.jar", 293_000)

        cache.rememberAbsent(guava, LibraryRootType.SOURCES)
        cache.rememberAbsent(gson, LibraryRootType.JAVADOC)

        assertTrue(cache.isKnownAbsent(guava, LibraryRootType.SOURCES))
        assertFalse(cache.isKnownAbsent(guava, LibraryRootType.JAVADOC))
        assertTrue(cache.isKnownAbsent(gson, LibraryRootType.JAVADOC))
        assertFalse(cache.isKnownAbsent(gson, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is not reported once the library jar has been replaced`() {
        val cache = cache()

        cache.rememberAbsent(cache.artifactKey("guava-33.0.0.jar", 3_048_576), LibraryRootType.SOURCES)

        assertFalse(cache.isKnownAbsent(cache.artifactKey("guava-33.0.0.jar", 4_000_000), LibraryRootType.SOURCES))
    }

    @Test
    fun `resolved maven coordinates are read back for the very same library jar`() {
        val cache = cache()
        val artifactKey = cache.artifactKey("gson-2.11.0.jar", 293_000)

        cache.rememberArtifactCoords(artifactKey, MavenArtifactCoords("com.google.code.gson", "gson", "2.11.0", "archive"))

        val coords = cache.knownArtifactCoords(artifactKey)

        assertEquals("com.google.code.gson", coords?.groupId)
        assertEquals("gson", coords?.artifactId)
        assertEquals("2.11.0", coords?.version)
    }

    @Test
    fun `the outcome of the previous lookup survives a restart`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)

        with(cache()) {
            rememberArtifactCoords(artifactKey, MavenArtifactCoords("com.google.code.gson", "gson", "2.11.0", "archive"))
            rememberAbsent(artifactKey, LibraryRootType.JAVADOC)
            flush()
        }

        val restarted = cache()

        assertEquals("com.google.code.gson", restarted.knownArtifactCoords(artifactKey)?.groupId)
        assertTrue(restarted.isKnownAbsent(artifactKey, LibraryRootType.JAVADOC))
        assertFalse(restarted.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is looked up again once a repository has been added`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)

        with(cache()) {
            rememberAbsent(artifactKey, LibraryRootType.SOURCES)
            flush()
        }

        val withNewRepository = cache().apply { load(listOf(CENTRAL, "https://nexus.acme.com/repository/maven-public")) }

        assertFalse(withNewRepository.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `resolved maven coordinates are kept once a repository has been added`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)

        with(cache()) {
            rememberArtifactCoords(artifactKey, MavenArtifactCoords("com.google.code.gson", "gson", "2.11.0", "archive"))
            flush()
        }

        val withNewRepository = cache().apply { load(listOf(CENTRAL, "https://nexus.acme.com/repository/maven-public")) }

        assertEquals("gson", withNewRepository.knownArtifactCoords(artifactKey)?.artifactId)
    }

    @Test
    fun `absent library root type is looked up again once the recorded absence is too old`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)
        val recordedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)

        with(cache(recordedAt)) {
            rememberAbsent(artifactKey, LibraryRootType.SOURCES)
            flush()
        }

        assertFalse(cache().isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is still reported while the recorded absence is recent enough`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)
        val recordedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(29)

        with(cache(recordedAt)) {
            rememberAbsent(artifactKey, LibraryRootType.SOURCES)
            flush()
        }

        assertTrue(cache().isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `a subsequent lookup does not reset the age of an already recorded absence`() {
        val artifactKey = cache().artifactKey("gson-2.11.0.jar", 293_000)
        val recordedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(29)

        with(cache(recordedAt)) {
            rememberAbsent(artifactKey, LibraryRootType.SOURCES)
            flush()
        }
        // a later lookup learns something else about another library jar and rewrites the very same cache
        with(cache()) {
            rememberAbsent(artifactKey("guava-33.0.0.jar", 3_048_576), LibraryRootType.JAVADOC)
            flush()
        }

        val afterTheLifespan = LibraryRootLookupCache(librarySourceDir, { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2) })
            .apply { load(listOf(CENTRAL)) }

        assertFalse(afterTheLifespan.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `an unreadable cache file is ignored`() {
        librarySourceDir.resolve("library-roots-lookup.json").toFile().writeText("not a json")

        val cache = cache()

        assertNull(cache.knownArtifactCoords(cache.artifactKey("gson-2.11.0.jar", 293_000)))
    }

    private fun cache(now: Long? = null) = LibraryRootLookupCache(librarySourceDir, { now ?: System.currentTimeMillis() })
        .apply { load(listOf(CENTRAL)) }

    companion object {
        private const val CENTRAL = "https://repo1.maven.org/maven2"
    }
}
