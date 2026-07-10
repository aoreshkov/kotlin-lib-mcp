package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.model.SourceLocation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import co.touchlab.kermit.Logger
import com.intellij.openapi.util.Disposer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Clock
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner

/**
 * [SourceAnalyzer] backed by the Kotlin Analysis API in standalone (K2/FIR) mode.
 *
 * One standalone session is built per [analyze] call (i.e. per coordinate) with the extracted
 * sources as source roots and [classpathRoots] + the JDK + the running kotlin-stdlib as binary
 * roots, then every file is walked once. Everything from `org.jetbrains.kotlin.*` stays inside
 * this package: symbols are converted to plain `model` types before they leave.
 *
 * Resolution failures degrade per-symbol, never per-library: a symbol whose types cannot be
 * resolved falls back to its PSI-only signature text with [ApiSymbol.bestEffort] set.
 */
public class AnalysisApiSourceAnalyzer : SourceAnalyzer {

    private val log = Logger.withTag("AnalysisApiSourceAnalyzer")

    override fun analyze(
        coordinate: LibraryCoordinate,
        sourceRoots: List<String>,
        classpathRoots: List<String>,
    ): LibraryIndex {
        val roots = sourceRoots.map { Path.of(it).toAbsolutePath().normalize() }.filter { it.exists() }
        val jars = classpathRoots.map { Path.of(it).toAbsolutePath().normalize() }.filter { it.exists() }
        val disposable = Disposer.newDisposable("kotlin-lib-mcp analysis of $coordinate")
        try {
            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform
                    val sdk = buildKtSdkModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "JDK"
                        addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = true)
                    }
                    val dependencies = buildKtLibraryModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "dependencies"
                        // Best-effort classpath + the stdlib this process runs with, so kotlin.*
                        // resolves even when the library's own dependency jars are missing.
                        addBinaryRoots(jars + listOfNotNull(runningKotlinStdlib()))
                    }
                    addModule(
                        buildKtSourceModule {
                            platform = JvmPlatforms.defaultJvmPlatform
                            moduleName = coordinate.toString()
                            addSourceRoots(roots)
                            addRegularDependency(sdk)
                            addRegularDependency(dependencies)
                        }
                    )
                }
            }
            val ktFiles = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
            log.i { "Analyzing $coordinate: ${ktFiles.size} Kotlin files, ${jars.size} classpath jars" }
            return buildIndex(coordinate, roots, ktFiles)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    // --- index assembly ---

    private fun buildIndex(
        coordinate: LibraryCoordinate,
        roots: List<Path>,
        ktFiles: List<KtFile>,
    ): LibraryIndex {
        val files = mutableListOf<SourceFileRef>()
        val symbols = LinkedHashMap<String, ApiSymbol>()
        val packageDeclarations = mutableMapOf<String, Int>()
        val packageTargets = mutableMapOf<String, MutableSet<KmpTarget>>()
        var degraded = 0

        for (ktFile in ktFiles) {
            val fileRef = fileRefFor(ktFile, roots) ?: continue
            files += fileRef
            val packageName = ktFile.packageFqName.asString()
            packageTargets.getOrPut(packageName, ::mutableSetOf) += fileRef.target

            val declarations = collectDeclarations(ktFile)
            val rendered = renderAll(ktFile, declarations)
            val lineStarts = lineStartOffsets(ktFile.text)
            for (declaration in declarations) {
                val symbol = toApiSymbol(declaration, rendered[declaration], fileRef, lineStarts) ?: continue
                if (symbol.bestEffort) degraded++
                putDeduplicated(symbols, symbol)
                packageDeclarations.merge(packageName, 1, Int::plus)
            }
        }

        if (degraded > 0) log.w { "$coordinate: $degraded of ${symbols.size} symbols indexed best-effort" }
        val packages = packageDeclarations.keys.sorted().map { name ->
            PackageInfo(
                name = name,
                declarationCount = packageDeclarations.getValue(name),
                targets = packageTargets[name].orEmpty().sorted(),
            )
        }
        return LibraryIndex(
            coordinate = coordinate,
            targets = files.map { it.target }.distinct().sorted(),
            packages = packages,
            symbolsByFqName = symbols,
            files = files,
            fetchedAt = Clock.System.now(),
        )
    }

    /** Renders all declarations of a file in one `analyze { }` block; failures degrade per symbol. */
    private fun renderAll(
        ktFile: KtFile,
        declarations: List<KtNamedDeclaration>,
    ): Map<KtNamedDeclaration, RenderedSymbol> =
        try {
            analyze(ktFile) {
                declarations.mapNotNull { declaration ->
                    runCatching { declaration to renderResolved(declaration.symbol) }
                        .onFailure { log.d { "Resolution failed for ${declaration.fqName}: $it" } }
                        .getOrNull()
                }.toMap()
            }
        } catch (e: Exception) {
            log.w(e) { "analyze() failed for ${ktFile.virtualFilePath}; falling back to PSI for the whole file" }
            emptyMap()
        }

    private fun toApiSymbol(
        declaration: KtNamedDeclaration,
        rendered: RenderedSymbol?,
        fileRef: SourceFileRef,
        lineStarts: IntArray,
    ): ApiSymbol? {
        val fqName = declaration.fqName?.asString() ?: return null
        val kind = kindOf(declaration) ?: return null
        val bestEffort = rendered == null || rendered.hasErrorTypes
        val offset = declarationStartOffset(declaration)
        return ApiSymbol(
            fqName = fqName,
            kind = kind,
            visibility = psiVisibility(declaration),
            signature = if (bestEffort) psiSignature(declaration) else rendered.signature,
            typeParameters = (declaration as? KtTypeParameterListOwner)?.typeParameters?.map { it.text }.orEmpty(),
            supertypes = rendered?.supertypes ?: psiSupertypes(declaration),
            modifiers = modifiersOf(declaration),
            kdoc = KDocExtractor.extract(declaration),
            sourceRef = SourceLocation(
                file = fileRef,
                line = lineAt(lineStarts, offset),
                offset = offset,
                endOffset = declaration.textRange.endOffset,
            ),
            bestEffort = bestEffort,
        )
    }

    // --- PSI helpers (used both for the happy path and the degraded path) ---

    /** Top-level + nested named declarations; skips locals, enum entries, and anonymous objects. */
    private fun collectDeclarations(ktFile: KtFile): List<KtNamedDeclaration> {
        val result = mutableListOf<KtNamedDeclaration>()
        fun visit(declarations: List<KtDeclaration>) {
            for (declaration in declarations) {
                when {
                    declaration is KtEnumEntry -> Unit
                    declaration is KtClassOrObject -> {
                        if (declaration.name != null) {
                            result += declaration
                            visit(declaration.declarations)
                        }
                    }
                    declaration is KtNamedFunction || declaration is KtProperty || declaration is KtTypeAlias ->
                        (declaration as KtNamedDeclaration).takeIf { it.name != null }?.let(result::add)
                }
            }
        }
        visit(ktFile.declarations)
        return result
    }

    private fun kindOf(declaration: KtNamedDeclaration): SymbolKind? = when (declaration) {
        is KtClass -> when {
            declaration.isInterface() -> SymbolKind.INTERFACE
            declaration.isEnum() -> SymbolKind.ENUM
            declaration.isAnnotation() -> SymbolKind.ANNOTATION
            else -> SymbolKind.CLASS
        }
        is KtObjectDeclaration -> SymbolKind.OBJECT
        is KtNamedFunction -> SymbolKind.FUNCTION
        is KtProperty -> SymbolKind.PROPERTY
        is KtTypeAlias -> SymbolKind.TYPEALIAS
        else -> null
    }

    private fun psiVisibility(declaration: KtNamedDeclaration): Visibility = when {
        declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
        declaration.hasModifier(KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
        declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) -> Visibility.INTERNAL
        else -> Visibility.PUBLIC
    }

    private fun modifiersOf(declaration: KtNamedDeclaration): List<String> =
        MODIFIERS.filter { declaration.hasModifier(it) }.map { it.value }

    /** Declared (non-visibility) modifiers worth surfacing, in a fixed, deterministic order. */
    private val MODIFIERS = listOf(
        KtTokens.EXPECT_KEYWORD, KtTokens.ACTUAL_KEYWORD,
        KtTokens.ABSTRACT_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.SEALED_KEYWORD, KtTokens.FINAL_KEYWORD,
        KtTokens.DATA_KEYWORD, KtTokens.VALUE_KEYWORD, KtTokens.INNER_KEYWORD, KtTokens.COMPANION_KEYWORD,
        KtTokens.OVERRIDE_KEYWORD, KtTokens.LATEINIT_KEYWORD, KtTokens.CONST_KEYWORD,
        KtTokens.SUSPEND_KEYWORD, KtTokens.INLINE_KEYWORD, KtTokens.OPERATOR_KEYWORD,
        KtTokens.INFIX_KEYWORD, KtTokens.TAILREC_KEYWORD, KtTokens.EXTERNAL_KEYWORD,
    )

    /** Unresolved supertype names straight from the class header, for the degraded path. */
    private fun psiSupertypes(declaration: KtNamedDeclaration): List<String> =
        (declaration as? KtClassOrObject)
            ?.superTypeListEntries
            ?.mapNotNull { it.typeReference?.text }
            .orEmpty()

    private fun fileRefFor(ktFile: KtFile, roots: List<Path>): SourceFileRef? {
        val filePath = runCatching { Path.of(ktFile.virtualFilePath).toAbsolutePath().normalize() }
            .getOrNull() ?: return null
        val root = roots.firstOrNull(filePath::startsWith) ?: return null
        val relative = root.relativize(filePath).joinToString("/")
        // Cache layout is `sources/<target>/<package dirs>/File.kt`. When the caller hands us the
        // per-target dir the target is the root's name; when it hands `sources/` itself the
        // target is the first path segment. Anything else stays UNKNOWN.
        val rootTarget = targetOf(root.fileName?.toString())
        return when {
            rootTarget != null ->
                SourceFileRef("${root.fileName}/$relative", ktFile.packageFqName.asString(), rootTarget)
            else -> {
                val firstSegment = relative.substringBefore('/')
                SourceFileRef(
                    path = relative,
                    packageName = ktFile.packageFqName.asString(),
                    target = targetOf(firstSegment) ?: KmpTarget.UNKNOWN,
                )
            }
        }
    }

    private fun targetOf(dirName: String?): KmpTarget? = when (dirName?.lowercase()) {
        "common" -> KmpTarget.COMMON
        "jvm" -> KmpTarget.JVM
        "js" -> KmpTarget.JS
        "native" -> KmpTarget.NATIVE
        "wasm" -> KmpTarget.WASM
        else -> null
    }

    /** Overloads share an FQ name; later ones get a deterministic `#n` suffix (file/offset order). */
    private fun putDeduplicated(symbols: MutableMap<String, ApiSymbol>, symbol: ApiSymbol) {
        var key = symbol.fqName
        var n = 1
        while (symbols.containsKey(key)) {
            n++
            key = "${symbol.fqName}#$n"
        }
        symbols[key] = symbol
    }

    /** Start of the declaration text proper: after its KDoc (if any) and the following whitespace. */
    private fun declarationStartOffset(declaration: KtNamedDeclaration): Int {
        val text = declaration.containingFile.text
        var start = declaration.docComment?.textRange?.endOffset ?: declaration.textRange.startOffset
        while (start < text.length && text[start].isWhitespace()) start++
        return start
    }

    private fun lineStartOffsets(text: String): IntArray {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun lineAt(lineStarts: IntArray, offset: Int): Int {
        val insertion = lineStarts.toList().binarySearch(offset)
        return if (insertion >= 0) insertion + 1 else -insertion - 1
    }

    private fun runningKotlinStdlib(): Path? =
        runCatching {
            Path.of(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()?.takeIf { it.exists() }
}
