package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.Deprecation
import com.intellij.openapi.util.Disposer
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * The degraded path on its own: [psiDeprecation] reads `Legacy.kt`'s annotations as text. The
 * analyzer only takes this path when a symbol fails to resolve entirely, which no fixture can make
 * happen on demand, so it is exercised here directly against parse-only PSI.
 */
class PsiDeprecationTest {

    private val disposable = Disposer.newDisposable("PsiDeprecationTest")

    private val declarations: Map<String, KtNamedDeclaration> by lazy {
        val root = Path.of(
            checkNotNull(PsiDeprecationTest::class.java.classLoader.getResource("fixtures/tiny-lib/common")) {
                "Missing test fixture: fixtures/tiny-lib/common"
            }.toURI()
        )
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                addModule(
                    buildKtSourceModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        moduleName = "psi-only"
                        addSourceRoot(root)
                    }
                )
            }
        }
        session.modulesWithFiles.values.flatten()
            .filterIsInstance<KtFile>()
            .single { it.name == "Legacy.kt" }
            .declarations
            .filterIsInstance<KtNamedDeclaration>()
            .associateBy { checkNotNull(it.name) }
    }

    @AfterTest
    fun dispose() {
        Disposer.dispose(disposable)
    }

    private fun deprecationOf(name: String): Deprecation? =
        psiDeprecation(assertNotNull(declarations[name], "no declaration '$name' in Legacy.kt"))

    @Test
    fun readsPositionalMessageReplaceWithAndImports() {
        assertEquals(
            Deprecation(
                level = Deprecation.Level.WARNING,
                message = "Use newGreet instead",
                replaceWith = "newGreet(name)",
                replaceWithImports = listOf("com.example.tiny.newGreet", "kotlin.text.trim"),
            ),
            deprecationOf("oldGreet"),
        )
    }

    @Test
    fun readsNamedArgumentsAndQualifiedNames() {
        assertEquals(
            Deprecation(
                level = Deprecation.Level.ERROR,
                message = "Greeting by number was a mistake",
                replaceWith = "newGreet(id.toString())",
            ),
            deprecationOf("greetById"),
        )
    }

    @Test
    fun readsAQualifiedLevel() {
        assertEquals(
            Deprecation(level = Deprecation.Level.HIDDEN, message = "Kept for binary compatibility"),
            deprecationOf("greetLoudly"),
        )
    }

    @Test
    fun readsAClassLevelDeprecation() {
        assertEquals(Deprecation(level = Deprecation.Level.WARNING, message = "Use DefaultGreeter"), deprecationOf("LegacyGreeter"))
    }

    @Test
    fun dropsAMessageItCannotReadLiterally() {
        assertEquals(Deprecation(level = Deprecation.Level.WARNING), deprecationOf("legacyGreeting"))
    }

    @Test
    fun leavesAnUndeprecatedDeclarationAlone() {
        assertNull(deprecationOf("newGreet"))
    }
}
