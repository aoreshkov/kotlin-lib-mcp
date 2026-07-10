package app.oreshkov.kotlinlibmcp.fetch

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** A `<dependency>` from a POM. [version] is `null` when BOM-managed or uninterpolatable. */
internal data class PomDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String? = null,
    val optional: Boolean = false,
)

internal data class PomInfo(
    val groupId: String?,
    val artifactId: String?,
    val version: String?,
    val parentGroupId: String? = null,
    val parentArtifactId: String? = null,
    val parentVersion: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val dependencies: List<PomDependency> = emptyList(),
) {
    val effectiveGroupId: String? get() = groupId ?: parentGroupId
    val effectiveVersion: String? get() = version ?: parentVersion

    /** Dependencies with `${...}` best-effort interpolated; unresolvable versions become `null`. */
    fun resolvedDependencies(): List<PomDependency> = dependencies.mapNotNull { dep ->
        val group = interpolate(dep.groupId) ?: return@mapNotNull null
        PomDependency(group, dep.artifactId, dep.version?.let(::interpolate), dep.scope, dep.optional)
    }

    private fun interpolate(raw: String): String? {
        if ('$' !in raw) return raw
        val resolved = PLACEHOLDER.replace(raw) { match ->
            val key = match.groupValues[1]
            properties[key] ?: when (key) {
                "project.version", "pom.version" -> effectiveVersion
                "project.groupId", "pom.groupId" -> effectiveGroupId
                else -> null
            } ?: match.value
        }
        return resolved.takeUnless { it.contains("\${") }
    }

    private companion object {
        val PLACEHOLDER = Regex("\\$\\{([^}]+)}")
    }
}

/**
 * Minimal StAX-based `pom.xml` reader: project GAV, `<parent>`, `<properties>` and top-level
 * `<dependencies>` (dependencyManagement is deliberately ignored). XXE-hardened: DTDs and
 * external entities are disabled.
 */
internal object PomParser {

    fun parse(xml: String): PomInfo {
        var groupId: String? = null
        var artifactId: String? = null
        var version: String? = null
        var parentGroupId: String? = null
        var parentArtifactId: String? = null
        var parentVersion: String? = null
        val properties = mutableMapOf<String, String>()
        val dependencies = mutableListOf<PomDependency>()

        var depGroup: String? = null
        var depArtifact: String? = null
        var depVersion: String? = null
        var depScope: String? = null
        var depOptional = false

        forEachLeaf(xml) { path, value ->
            when (path) {
                "project/groupId" -> groupId = value
                "project/artifactId" -> artifactId = value
                "project/version" -> version = value
                "project/parent/groupId" -> parentGroupId = value
                "project/parent/artifactId" -> parentArtifactId = value
                "project/parent/version" -> parentVersion = value
                "project/dependencies/dependency/groupId" -> depGroup = value
                "project/dependencies/dependency/artifactId" -> depArtifact = value
                "project/dependencies/dependency/version" -> depVersion = value
                "project/dependencies/dependency/scope" -> depScope = value
                "project/dependencies/dependency/optional" -> depOptional = value.toBoolean()
                "project/dependencies/dependency" -> {
                    val group = depGroup
                    val artifact = depArtifact
                    if (group != null && artifact != null) {
                        dependencies += PomDependency(group, artifact, depVersion, depScope, depOptional)
                    }
                    depGroup = null; depArtifact = null; depVersion = null; depScope = null; depOptional = false
                }
                else -> if (path.startsWith("project/properties/") && path.count { it == '/' } == 2) {
                    properties[path.substringAfterLast('/')] = value
                }
            }
        }

        return PomInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            parentGroupId = parentGroupId,
            parentArtifactId = parentArtifactId,
            parentVersion = parentVersion,
            properties = properties,
            dependencies = dependencies,
        )
    }

    /** Walks the document, invoking [onElementEnd] with the `/`-joined element path and its text. */
    private inline fun forEachLeaf(xml: String, onElementEnd: (path: String, value: String) -> Unit) {
        val reader = XmlSupport.newReader(xml)
        try {
            val path = ArrayDeque<String>()
            var text = StringBuilder()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        path.addLast(reader.localName)
                        text = StringBuilder()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        onElementEnd(path.joinToString("/"), text.toString().trim())
                        path.removeLast()
                    }
                }
            }
        } finally {
            reader.close()
        }
    }
}

/** Metadata read from `maven-metadata.xml`: the version list plus the `release`/`latest` tags. */
internal data class MavenMetadata(
    val versions: List<String>,
    val release: String? = null,
    val latest: String? = null,
)

/** Parses `maven-metadata.xml`; used for `list_versions` / `get_latest_version`. Hardened StAX setup. */
internal object MavenMetadataParser {

    /** All `<version>` entries in document order (repositories list oldest → newest). */
    fun parseVersions(xml: String): List<String> = parse(xml).versions

    /** Full versioning info: `<versions>`, plus the `<release>` and `<latest>` tags when present. */
    fun parse(xml: String): MavenMetadata {
        val versions = mutableListOf<String>()
        var release: String? = null
        var latest: String? = null
        val reader = XmlSupport.newReader(xml)
        try {
            val path = ArrayDeque<String>()
            var text = StringBuilder()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        path.addLast(reader.localName)
                        text = StringBuilder()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val value = text.toString().trim().takeIf { it.isNotEmpty() }
                        when (path.joinToString("/")) {
                            "metadata/versioning/versions/version" -> value?.let(versions::add)
                            "metadata/versioning/release" -> release = value
                            "metadata/versioning/latest" -> latest = value
                        }
                        path.removeLast()
                    }
                }
            }
        } finally {
            reader.close()
        }
        return MavenMetadata(versions = versions, release = release, latest = latest)
    }
}

internal object XmlSupport {

    /** StAX reader with XXE hardening: no DTD processing, no external entity resolution. */
    fun newReader(xml: String): XMLStreamReader {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        return factory.createXMLStreamReader(StringReader(xml))
    }
}
