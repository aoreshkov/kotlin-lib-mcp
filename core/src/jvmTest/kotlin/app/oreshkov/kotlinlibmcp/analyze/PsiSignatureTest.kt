package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.Visibility
import com.intellij.openapi.util.Disposer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * The degraded path on its own: [psiSignature] and [psiEffectiveVisibility] over parse-only PSI,
 * for shapes no fixture can make degrade on demand.
 */
class PsiSignatureTest {

    private val disposable = Disposer.newDisposable("PsiSignatureTest")

    private val factory: KtPsiFactory by lazy {
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider { platform = JvmPlatforms.defaultJvmPlatform }
        }
        KtPsiFactory(session.project)
    }

    @AfterTest
    fun dispose() {
        Disposer.dispose(disposable)
    }

    private fun declaration(source: String): KtNamedDeclaration =
        factory.createFile("Sample.kt", source).declarations.single() as KtNamedDeclaration

    private fun signatureOf(source: String): String = psiSignature(declaration(source))

    @Test
    fun keepsAClassNameEndingInBy() {
        assertEquals("class Lobby", signatureOf("class Lobby"))
    }

    @Test
    fun keepsATypeAliasTargetEndingInBy() {
        assertEquals("typealias X = Hobby", signatureOf("typealias X = Hobby"))
    }

    @Test
    fun cutsADelegatedPropertyBeforeItsKeyword() {
        assertEquals("val greeting: String", signatureOf("val greeting: String by lazy { \"hi\" }"))
    }

    @Test
    fun cutsAConstructorPropertyBeforeItsDefault() {
        val parameter = (declaration("class Box(val size: Int = 3)") as KtClassOrObject).primaryConstructorParameters.single()

        assertEquals("val size: Int", psiSignature(parameter))
    }

    @Test
    fun narrowsAMemberToItsEnclosingClasses() {
        val outer = declaration("public class Outer { internal class Inner { protected fun f() {} } }") as KtClassOrObject
        val inner = outer.declarations.single() as KtClassOrObject

        assertEquals(Visibility.INTERNAL, psiEffectiveVisibility(inner.declarations.single()))
    }
}
