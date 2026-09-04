/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for IntelliJ IDEA.
 * Copyright (C) 2019-2025 EPAM Systems <hybrisideaplugin@epam.com> and contributors
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

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.application
import com.intellij.util.asSafely
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import sap.commerce.toolset.util.fileExists
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.*
import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * Custom implementation of the maven artifact searcher required to support caching and advanced groupId identification and better artifact search by hash.
 */
@Service
class LibraryRootLookupService {

    suspend fun findJarUrls(
        lookupCache: LibraryRootLookupCache,
        lookupRepositories: List<String>,
        libraryJar: VirtualFile,
        libraryRootLookups: Collection<LibraryRootLookup>
    ) {
        if (libraryRootLookups.isEmpty()) return

        checkCanceled()

        val artifactKey = lookupCache.artifactKey(libraryJar.name, libraryJar.length)
        val lookups = libraryRootLookups
            .filterNot { lookupCache.isKnownAbsent(artifactKey, it.type) }
            .takeIf { it.isNotEmpty() }
            ?: return
        val mavenArtifactCoords = findMavenArtifactCoords(lookupCache, lookupRepositories, libraryJar, artifactKey) ?: return

        // sources and javadocs are resolved independently, an artifact may provide only one of them
        lookups.forEach {
            checkCanceled()

            val remoteLookup = findRemote(lookupRepositories) { baseUrl -> mavenArtifactCoords.toUrl(baseUrl, it.type) }

            it.url = remoteLookup.url

            if (remoteLookup.absent) lookupCache.rememberAbsent(artifactKey, it.type)
        }
    }

    private suspend fun findMavenArtifactCoords(
        lookupCache: LibraryRootLookupCache,
        lookupRepositories: List<String>,
        libraryJar: VirtualFile,
        artifactKey: String
    ): MavenArtifactCoords? {
        lookupCache.knownArtifactCoords(artifactKey)?.let { return it }

        // most common approach -> maven packaging META-INF/maven
        val localCoords = readMavenCoordsFromArchive(libraryJar)
            // example: accessors-smart-2.5.2.jar
            ?: guessByBundleInManifestMF(libraryJar)
            // example: activation-1.1.1.jar
            ?: guessByExtensionNameInManifestMF(libraryJar)
        val mavenArtifactCoords = localCoords?.takeIf { availableInRemote(lookupRepositories, it) }
            // if nothing helps -> fallback to search by SHA1 of the respective jar file
            ?: findMavenArtifactCoordsBySha1(lookupRepositories, libraryJar)
            ?: return null

        // remember it only if the artifact exists in remote
        lookupCache.rememberArtifactCoords(artifactKey, mavenArtifactCoords)

        return mavenArtifactCoords
    }

    private suspend fun findMavenArtifactCoordsBySha1(lookupRepositories: List<String>, libraryJar: VirtualFile): MavenArtifactCoords? {
        val solrCoords = getExternalMavenCoords(libraryJar) ?: return null
        val mavenArtifactCoords = MavenArtifactCoords.from(solrCoords)

        return mavenArtifactCoords.takeIf { availableInRemote(lookupRepositories, it) }
    }

    private suspend fun availableInRemote(lookupRepositories: List<String>, mavenArtifactCoords: MavenArtifactCoords) =
        findRemote(lookupRepositories) { baseUrl -> mavenArtifactCoords.toUrl(baseUrl) }.url != null

    private suspend fun getExternalMavenCoords(libraryJar: VirtualFile): SolrMavenArtifactCoords? {
        checkCanceled()

        val sha1 = withContext(Dispatchers.IO) {
            libraryJar.toNioPath()
                .takeIf { it.fileExists }
                ?.toFile()
                ?.sha1()
        }
            ?: return null
        val url = "https://central.sonatype.com/solrsearch/select?rows=1&wt=json&q=1:$sha1"

        try {
            return withContext(Dispatchers.IO) {
                HttpRequests.request(url)
                    .accept("application/json")
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .connect { processor ->
                        JSON.decodeFromString<SolrResponse>(processor.readString()).response
                            .docs
                            .firstOrNull()
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().debug("Solr MavenCoords not found for: ${libraryJar.nameWithoutExtension}, $url, due: ${e.message}")
        }
        return null
    }

    private suspend fun findRemote(baseUrls: Collection<String>, urlProvider: (String) -> String): RemoteLookup {
        var absent = baseUrls.isNotEmpty()

        baseUrls.forEach { baseUrl ->
            val url = urlProvider(baseUrl)

            when (remoteExists(url)) {
                true -> return RemoteLookup(url = url)
                false -> Unit
                // availability stays unknown, so the outcome must not be remembered as absent
                null -> absent = false
            }
        }

        return RemoteLookup(absent = absent)
    }

    /**
     * `true` - the artifact is available, `false` - the repository responded that the artifact does not exist,
     * `null` - the repository cannot be reached, availability of the artifact stays unknown.
     */
    private suspend fun remoteExists(url: String): Boolean? {
        checkCanceled()

        try {
            return withContext(Dispatchers.IO) {
                HttpRequests.head(url)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .connect { processor ->
                        processor.connection
                            .asSafely<HttpURLConnection>()
                            ?.responseCode == HttpURLConnection.HTTP_OK
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequests.HttpStatusException) {
            if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                thisLogger().debug("Resource does not exist: $url")

                return false
            }
            thisLogger().debug("Resource is not accessible: $url, due: ${e.message}")
        } catch (e: Exception) {
            thisLogger().debug("Repository is not reachable for: $url, due: ${e.message}")
        }

        return null
    }

    private suspend fun guessByBundleInManifestMF(libraryJar: VirtualFile) = readManifestMF(libraryJar) { attributes ->
        val bundleSymbolicName = attributes.getValue("Bundle-SymbolicName") ?: return@readManifestMF null
        val version = attributes.getValue("Bundle-Version") ?: return@readManifestMF null
        val jarName = libraryJar.nameWithoutExtension
        val groupId = "$bundleSymbolicName-$version".removeSuffix(jarName)
            .takeIf { it.isNotBlank() } ?: return@readManifestMF null
        val artifactId = jarName.removeSuffix("-$version")

        MavenArtifactCoords(groupId, artifactId, version, "manifest-bundle")
    }

    private suspend fun guessByExtensionNameInManifestMF(libraryJar: VirtualFile) = readManifestMF(libraryJar) { attributes ->
        val groupId = attributes.getValue("Extension-Name")
        // special case for Tomcat libs
            ?: attributes.getValue("Implementation-Title")
                ?.takeIf { it == "Apache Tomcat" }
                ?.let { "org.apache.tomcat" }
            ?: return@readManifestMF null
        val version = attributes.getValue("Implementation-Version")
            ?.substringBefore(" ")
            ?: return@readManifestMF null
        val jarName = libraryJar.nameWithoutExtension
        val artifactId = jarName.removeSuffix("-$version")

        MavenArtifactCoords(groupId, artifactId, version, "manifest-extension")
    }

    private suspend fun readManifestMF(libraryJar: VirtualFile, mapper: (Attributes) -> MavenArtifactCoords?): MavenArtifactCoords? {
        checkCanceled()

        try {
            return withContext(Dispatchers.IO) {
                JarFile(VfsUtilCore.virtualToIoFile(libraryJar))
                    .use { jarFile ->
                        jarFile.manifest?.mainAttributes
                            ?.let { attributes -> mapper(attributes) }
                    }
            }
        } catch (_: IOException) {
            // NOOP
        }
        return null
    }

    private suspend fun readMavenCoordsFromArchive(libraryJar: VirtualFile): MavenArtifactCoords? {
        checkCanceled()

        try {
            withContext(Dispatchers.IO) {
                JarFile(VfsUtilCore.virtualToIoFile(libraryJar))
            }.use { jarFile ->
                val entries = jarFile.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.getName()
                    if (REGEX_POM_PROPERTIES.matches(name)) {
                        return jarFile.getInputStream(entry).use { pomEntry ->
                            val props = Properties().apply { load(pomEntry) }

                            MavenArtifactCoords(
                                groupId = props.getProperty("groupId"),
                                artifactId = props.getProperty("artifactId"),
                                version = props.getProperty("version"),
                                source = "archive",
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("no maven coordinates MF for ${libraryJar.name}, due: ${e.message}")
        }

        return null
    }

    private fun File.sha1(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        inputStream().use { fis ->
            val buffer = ByteArray(1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DELIMITER = "|"
        private const val CONNECT_TIMEOUT = 3000
        private const val READ_TIMEOUT = 3000
        private val REGEX_POM_PROPERTIES = "META-INF/maven.+/pom\\.properties".toRegex()
        private val JSON = Json { ignoreUnknownKeys = true }

        fun getService(): LibraryRootLookupService = application.service()
    }
}

private data class RemoteLookup(
    val url: String? = null,
    val absent: Boolean = false,
)
