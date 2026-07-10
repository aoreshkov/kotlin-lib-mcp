package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.PackageList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListPackagesTool(service: LibraryService) {
    addTool(
        name = "list_packages",
        description = "List the packages discovered in a fetched library's sources, with " +
            "declaration counts and the KMP targets each package appears in.",
        inputSchema = coordinateSchema(),
        title = "List packages",
        outputSchema = outputSchemaOf<PackageList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded { toolResult(service.listPackages(request.args().coordinateArg())) }
    }
}
