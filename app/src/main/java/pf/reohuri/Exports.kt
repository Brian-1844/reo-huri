package pf.reohuri

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Enregistrement du texte : .txt, .srt, .docx (Word). */
object Exports {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "</Types>"
    private const val RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"
    private const val DOC_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"
    private const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:styles xmlns:w=\"$W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\"/>" +
        "</w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
        "</w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/>" +
        "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>" +
        "</w:styles>"

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun run(texte: String) = "<w:r><w:t xml:space=\"preserve\">${xml(texte)}</w:t></w:r>"

    fun docxXml(titre: String, source: String, paras: List<Bloc>, horodatage: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<w:document xmlns:w=\"$W\"><w:body>")
        sb.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>${run(titre)}</w:p>")
        if (source.isNotEmpty()) sb.append("<w:p>${run(source)}</w:p>")
        for (p in paras) {
            // L'horodatage seul sur sa ligne : pipeline.py le reconnaît et le conserve
            if (horodatage) sb.append("<w:p>${run(Texte.heure(p.debut))}</w:p>")
            sb.append("<w:p>${run(p.texte)}</w:p>")
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        sb.append("<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>")
        sb.append("</w:sectPr></w:body></w:document>")
        return sb.toString()
    }

    fun ecrireDocx(sortie: OutputStream, titre: String, source: String, paras: List<Bloc>, horodatage: Boolean) {
        ZipOutputStream(sortie).use { z ->
            fun entree(nom: String, contenu: String) {
                z.putNextEntry(ZipEntry(nom))
                z.write(contenu.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entree("[Content_Types].xml", TYPES)
            entree("_rels/.rels", RELS)
            entree("word/_rels/document.xml.rels", DOC_RELS)
            entree("word/styles.xml", STYLES)
            entree("word/document.xml", docxXml(titre, source, paras, horodatage))
        }
    }

    fun texteBrut(titre: String, source: String, paras: List<Bloc>, horodatage: Boolean): String =
        titre + "\n" + (if (source.isNotEmpty()) source + "\n" else "") + "\n" +
            Texte.formatTexte(paras, horodatage) + "\n"
}
