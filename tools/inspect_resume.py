from __future__ import annotations

import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

PDF_PATH = r"E:\简历\简历.pdf"
DOCX_PATHS = [
    r"E:\简历\简历.docx",
    r"E:\简历\简历_new.docx",
    r"E:\简历\简历_new - 副本.docx",
    r"E:\简历\简历_v2.docx",
]


def inspect_pdf() -> None:
    import pdfplumber

    with pdfplumber.open(PDF_PATH) as pdf:
        print("PDF pages", len(pdf.pages))
        page = pdf.pages[0]
        print("PDF page size", page.width, page.height)
        words = page.extract_words(x_tolerance=2, y_tolerance=3, keep_blank_chars=False)
        print("--- PDF words first page ---")
        for word in words[:180]:
            print(
                f"{word['x0']:.1f},{word['top']:.1f},"
                f"{word['x1']:.1f},{word['bottom']:.1f}: {word['text']}"
            )


def inspect_docx() -> None:
    from docx import Document

    for path in DOCX_PATHS:
        if not os.path.exists(path):
            continue
        print("\n---", os.path.basename(path), "---")
        doc = Document(path)
        text = "\n".join(p.text for p in doc.paragraphs if p.text.strip())
        print(text[:2200])


if __name__ == "__main__":
    inspect_pdf()
    inspect_docx()
