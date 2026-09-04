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

package sap.commerce.toolset.java.settings.state

import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag

/**
 * Outcome of the previous Maven artifact lookups, both keyed by the identity of the respective library jar.
 *
 * Remote repositories are queried only for the entries which are absent here, therefore the very same jar is
 * looked up over the network once instead of on each Project Refresh and IDE restart.
 */
@Tag("HybrisLibraryRootLookup")
data class LibraryRootLookupState(

    /**
     * Maven coordinates resolved for a library jar which is available in one of the remote repositories.
     */
    @JvmField @OptionTag val artifactCoords: Map<String, String> = emptyMap(),

    /**
     * Library root types which the remote repositories explicitly reported as non-existing for a library jar.
     */
    @JvmField @OptionTag val absentRootTypes: Map<String, Set<String>> = emptyMap(),
)
