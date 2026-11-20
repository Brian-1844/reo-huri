"""Enregistrement du texte : .txt, .srt, .docx (Word)."""
from __future__ import annotations

import zipfile
from xml.sax.saxutils import escape

from .texte import Bloc, format_texte, heure, vers_srt

_TYPES = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
          '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
          '</Types>')
_RELS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
         '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
         '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
         '</Relationships>')
_DOC_RELS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
             '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
             '</Relationships>')
_STYLES = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
           '<w:docDefaults><w:rPrDefault><w:rPr>'
           '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>'
           '<w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="en-US"/>'
           '</w:rPr></w:rPrDefault>'
           '<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>'
           '</w:docDefaults>'
           '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
           '<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>'
           '<w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr></w:style>'
           '</w:styles>')
_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def _run(texte: str, gras: bool = False) -> str:
    rpr = "<w:rPr><w:b/></w:rPr>" if gras else ""
    return f'<w:r>{rpr}<w:t xml:space="preserve">{escape(texte)}</w:t></w:r>'


def docx_xml(titre: str, source: str, paras: list[Bloc], horodatage: bool) -> str:
    corps = [f'<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr>{_run(titre)}</w:p>']
    if source:
        corps.append(f"<w:p>{_run(source)}</w:p>")
    for p in paras:
        if horodatage:          # l'horodatage seul sur sa ligne : pipeline.py le reconnaît et le conserve
            corps.append(f"<w:p>{_run(heure(p.debut))}</w:p>")
        corps.append(f"<w:p>{_run(p.texte)}</w:p>")
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<w:document xmlns:w="{_W}"><w:body>' + "".join(corps) +
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/>'
            '</w:sectPr></w:body></w:document>')


def ecrire_docx(chemin: str, titre: str, source: str, paras: list[Bloc], horodatage: bool = True) -> None:
    with zipfile.ZipFile(chemin, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", _TYPES)
        z.writestr("_rels/.rels", _RELS)
        z.writestr("word/_rels/document.xml.rels", _DOC_RELS)
        z.writestr("word/styles.xml", _STYLES)
        z.writestr("word/document.xml", docx_xml(titre, source, paras, horodatage))


def ecrire_txt(chemin: str, titre: str, source: str, paras: list[Bloc], horodatage: bool = True) -> None:
    tete = titre + "\n" + (source + "\n" if source else "") + "\n"
    with open(chemin, "w", encoding="utf-8") as f:
        f.write(tete + format_texte(paras, horodatage) + "\n")


def ecrire_srt(chemin: str, paras: list[Bloc]) -> None:
    with open(chemin, "w", encoding="utf-8") as f:
        f.write(vers_srt(paras))
