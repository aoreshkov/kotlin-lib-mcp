package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.KDoc
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.api.KDoc as KDocPsi
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import app.oreshkov.kotlinlibmcp.model.KDocTag as KDocTagModel

/**
 * Reads a declaration's KDoc straight from PSI (`docComment`) — no resolution involved, so it
 * works identically for fully resolved and `bestEffort` symbols.
 */
internal object KDocExtractor {

    private val paragraphBreak = Regex("""\n\s*\n""")
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")

    fun extract(declaration: KtDeclaration): KDoc? {
        val doc = declaration.docComment
            ?: return (declaration as? KtParameter)?.let(::fromPropertyTag)
        val content = doc.getDefaultSection().getContent().trim()
        val tags = tagsOf(doc).mapNotNull { tag ->
            val name = tag.name ?: return@mapNotNull null // the default section carries no tag name
            val value = listOfNotNull(
                tag.getSubjectName(),
                tag.getContent().trim().takeIf(String::isNotEmpty),
            ).joinToString(" ")
            KDocTagModel(name, value)
        }
        return of(content, tags)
    }

    /**
     * A primary-constructor `val`/`var` is documented by its class, with `@property <name>` — the
     * form KDoc prescribes for it — so that tag's text is the property's own KDoc.
     */
    private fun fromPropertyTag(parameter: KtParameter): KDoc? {
        val classDoc = parameter.containingClassOrObject?.docComment ?: return null
        val tag = tagsOf(classDoc).firstOrNull { it.name == "property" && it.getSubjectName() == parameter.name }
        return tag?.let { of(it.getContent().trim(), tags = emptyList()) }
    }

    private fun tagsOf(doc: KDocPsi): Collection<KDocTag> = PsiTreeUtil.findChildrenOfType(doc, KDocTag::class.java)

    private fun of(content: String, tags: List<KDocTagModel>): KDoc? {
        if (content.isEmpty() && tags.isEmpty()) return null

        // Summary = first sentence of the first paragraph; everything after it is description.
        val paragraphs = content.split(paragraphBreak, limit = 2)
        val sentences = paragraphs.first().split(sentenceEnd, limit = 2)
        val summary = sentences.first().trim()
        val description = listOfNotNull(
            sentences.getOrNull(1),
            paragraphs.getOrNull(1),
        ).joinToString("\n\n").trim().takeIf(String::isNotEmpty)

        return KDoc(summary = summary, description = description, tags = tags)
    }
}
