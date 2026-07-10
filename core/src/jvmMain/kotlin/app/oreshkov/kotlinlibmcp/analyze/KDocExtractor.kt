package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.KDoc
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtDeclaration
import app.oreshkov.kotlinlibmcp.model.KDocTag as KDocTagModel

/**
 * Reads a declaration's KDoc straight from PSI (`docComment`) — no resolution involved, so it
 * works identically for fully resolved and `bestEffort` symbols.
 */
internal object KDocExtractor {

    private val paragraphBreak = Regex("""\n\s*\n""")
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")

    fun extract(declaration: KtDeclaration): KDoc? {
        val doc = declaration.docComment ?: return null
        val content = doc.getDefaultSection().getContent().trim()
        val tags = PsiTreeUtil.findChildrenOfType(doc, KDocTag::class.java).mapNotNull { tag ->
            val name = tag.name ?: return@mapNotNull null // the default section carries no tag name
            val value = listOfNotNull(
                tag.getSubjectName(),
                tag.getContent().trim().takeIf(String::isNotEmpty),
            ).joinToString(" ")
            KDocTagModel(name, value)
        }
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
