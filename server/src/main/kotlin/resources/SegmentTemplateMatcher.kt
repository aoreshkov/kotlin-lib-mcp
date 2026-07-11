package app.oreshkov.kotlinlibmcp.server.resources

import io.ktor.http.decodeURLPart
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory

/**
 * Replacement for the SDK's `PathSegmentTemplateMatcher`, which crashes here with
 * `NoSuchMethodError: ExtensionsKt.toImmutableMap` — `kotlin-compiler` (pulled in by `core`
 * for the Analysis API) bundles an old, unrelocated copy of `kotlinx.collections.immutable`
 * that shadows the SDK's 0.5.x dependency on the runtime classpath. Same RFC 6570 Level 1
 * semantics and specificity scoring (literal segment = 2, variable capture = 1), built on
 * plain collections only.
 *
 * Captured variables are percent-decoded once and remain untrusted input — read handlers
 * must validate them before use (see [registerLibraryIndexTemplate]).
 */
internal val segmentTemplateMatcherFactory: ResourceTemplateMatcherFactory =
    ResourceTemplateMatcherFactory { template ->
        object : ResourceTemplateMatcher {
            override val resourceTemplate = template
            private val templateParts = template.uriTemplate.trim('/').split("/")

            override fun match(resourceUri: String): MatchResult? {
                if (resourceUri.length > MAX_URI_LENGTH) return null
                val uriParts = resourceUri.trim('/').split("/")
                if (uriParts.size != templateParts.size) return null

                val variables = mutableMapOf<String, String>()
                var score = 0
                for (i in templateParts.indices) {
                    val part = templateParts[i]
                    val segment = uriParts[i].decodeURLPart()
                    val variableName = part
                        .takeIf { it.length > 2 && it.startsWith("{") && it.endsWith("}") }
                        ?.removeSurrounding("{", "}")?.trim()
                    when {
                        variableName != null -> {
                            variables[variableName] = segment
                            score += 1
                        }
                        part == segment -> score += 2
                        else -> return null
                    }
                }
                return MatchResult(variables = variables, score = score)
            }
        }
    }

private const val MAX_URI_LENGTH = 2048
