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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRootLookupServiceTest {

    @Test
    fun `nothing is known about a library jar which was never looked up`() {
        val service = LibraryRootLookupService()
        val artifactKey = service.artifactKey("guava-33.0.0.jar", 3_048_576)

        assertFalse(service.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
        assertNull(service.knownArtifactCoords(artifactKey))
    }

    @Test
    fun `absent library root type is remembered for the very same library jar`() {
        val service = LibraryRootLookupService()
        val artifactKey = service.artifactKey("guava-33.0.0.jar", 3_048_576)

        service.rememberAbsent(artifactKey, LibraryRootType.SOURCES)

        assertTrue(service.isKnownAbsent(artifactKey, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is not reported for another library root type`() {
        val service = LibraryRootLookupService()
        val artifactKey = service.artifactKey("guava-33.0.0.jar", 3_048_576)

        service.rememberAbsent(artifactKey, LibraryRootType.SOURCES)

        assertFalse(service.isKnownAbsent(artifactKey, LibraryRootType.JAVADOC))
    }

    @Test
    fun `absent library root types are remembered independently per library jar`() {
        val service = LibraryRootLookupService()
        val guava = service.artifactKey("guava-33.0.0.jar", 3_048_576)
        val gson = service.artifactKey("gson-2.11.0.jar", 293_000)

        service.rememberAbsent(guava, LibraryRootType.SOURCES)
        service.rememberAbsent(gson, LibraryRootType.JAVADOC)

        assertTrue(service.isKnownAbsent(guava, LibraryRootType.SOURCES))
        assertFalse(service.isKnownAbsent(guava, LibraryRootType.JAVADOC))
        assertTrue(service.isKnownAbsent(gson, LibraryRootType.JAVADOC))
        assertFalse(service.isKnownAbsent(gson, LibraryRootType.SOURCES))
    }

    @Test
    fun `absent library root type is not reported once the library jar has been replaced`() {
        val service = LibraryRootLookupService()

        service.rememberAbsent(service.artifactKey("guava-33.0.0.jar", 3_048_576), LibraryRootType.SOURCES)

        assertFalse(service.isKnownAbsent(service.artifactKey("guava-33.0.0.jar", 4_000_000), LibraryRootType.SOURCES))
    }

    @Test
    fun `resolved maven coordinates are read back for the very same library jar`() {
        val service = LibraryRootLookupService()
        val artifactKey = service.artifactKey("gson-2.11.0.jar", 293_000)

        service.rememberArtifactCoords(artifactKey, MavenArtifactCoords("com.google.code.gson", "gson", "2.11.0", "archive"))

        val coords = service.knownArtifactCoords(artifactKey)

        assertEquals("com.google.code.gson", coords?.groupId)
        assertEquals("gson", coords?.artifactId)
        assertEquals("2.11.0", coords?.version)
    }

    @Test
    fun `maven coordinates containing the key delimiter are not remembered`() {
        val service = LibraryRootLookupService()
        val artifactKey = service.artifactKey("broken-1.0.jar", 1_024)

        service.rememberArtifactCoords(artifactKey, MavenArtifactCoords("com.example", "broken|artifact", "1.0", "archive"))

        assertNull(service.knownArtifactCoords(artifactKey))
    }
}
