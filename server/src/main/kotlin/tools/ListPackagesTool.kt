package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.PackageList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import app.oreshkov.kotlinlibmcp.server.resources.libraryPackageLink
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Packages of a page that also get a `resource_link`. A link is ~150 bytes and a page can hold
 * 1 000 packages, so linking every one would add more than the page itself; the first few are the
 * ones a caller is likely to open.
 */
internal const val MAX_PACKAGE_LINKS = 20

fun Server.registerListPackagesTool(service: LibraryService) {
    addTool(
        name = "list_packages",
        description = "List the packages discovered in a fetched library's sources, with " +
            "declaration counts and the KMP targets each package appears in. Results are paged " +
            "('truncated: true' with a 'totalCount' when the library has more than the returned " +
            "page; advance 'offset' to fetch the rest). The first $MAX_PACKAGE_LINKS packages of " +
            "the page also come as resource links to their public API.",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "maxResults" to intProp(
                    "Page size, 1-1000 (default 200)",
                    minimum = 1,
                    maximum = LibraryService.MAX_PACKAGE_RESULTS,
                    default = LibraryService.DEFAULT_PACKAGE_RESULTS,
                ),
                "offset" to intProp("Number of packages to skip for paging (default 0)", minimum = 0, default = 0),
            ),
        ),
        title = "List packages",
        outputSchema = outputSchemaOf<PackageList>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Packages,
    ) { request ->
        guarded(request, revealInternalErrors = service.exposeLocalPaths) {
            val args = request.args()
            val page = service.listPackages(
                coordinate = args.coordinateArg(),
                maxResults = args.intArg("maxResults") ?: LibraryService.DEFAULT_PACKAGE_RESULTS,
                offset = args.intArg("offset") ?: 0,
            )
            val links = page.packages.asSequence()
                .mapNotNull { libraryPackageLink(page.coordinate, it.name) }
                .take(MAX_PACKAGE_LINKS)
                .toList()
            toolResult(page, links)
        }
    }
}
