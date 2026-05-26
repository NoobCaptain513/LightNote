from __future__ import annotations

import io
from pathlib import Path

from PyPDF2 import PdfReader, PdfWriter
from reportlab.lib.colors import black, white
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfgen import canvas

SRC = Path(r"E:\简历\简历.pdf")
OUT = Path(r"D:\javaproject\LightNote\resume_modified.pdf")

FONT = "STSong-Light"


def y_from_top(page_height: float, top: float) -> float:
    return page_height - top


def draw_line(c: canvas.Canvas, text: str, x: float, top: float, size: float) -> None:
    c.setFont(FONT, size)
    c.setFillColor(black)
    c.drawString(x, y_from_top(841.92004, top), text)


def make_overlay(width: float, height: float) -> bytes:
    packet = io.BytesIO()
    c = canvas.Canvas(packet, pagesize=(width, height))
    pdfmetrics.registerFont(UnicodeCIDFont(FONT))

    # Header: keep the original vertical positions, move the text block to the left margin.
    c.setFillColor(white)
    c.rect(0, height - 96, width, 65, fill=1, stroke=0)
    draw_line(c, "徐子涵 - Java 后端开发实习生", 42.5, 49.5, 15.0)
    draw_line(c, "性别：男  |  年龄：21  |  到岗时间：一周", 42.5, 74.4, 9.0)
    draw_line(c, "电话：18712150513  |  邮箱：3366029071@qq.com", 42.5, 91.6, 9.0)

    # First project: add source URL on the existing title/date row.
    draw_line(c, "源码：https://github.com/NoobCaptain513/LightNote", 323.0, 172.2, 7.2)

    # Replace the Agent implementation responsibility, removing the manual/native wording.
    c.setFillColor(white)
    c.rect(38, height - 268, 520, 28, fill=1, stroke=0)
    draw_line(
        c,
        "• 独立设计AI Agent店铺搜索系统，基于Spring AI @Tool标准化工具声明与LangChain4j AiServices声明式Agent实现，",
        42.5,
        252.8,
        7.35,
    )
    draw_line(
        c,
        "  支持自然语言多轮对话、Tool Calling、RAG检索增强与SSE流式输出，并通过@ConditionalOnProperty配置化切换Provider",
        42.5,
        266.4,
        7.35,
    )

    c.save()
    return packet.getvalue()


def main() -> None:
    reader = PdfReader(str(SRC))
    writer = PdfWriter()
    first = reader.pages[0]
    width = float(first.mediabox.width)
    height = float(first.mediabox.height)
    overlay_reader = PdfReader(io.BytesIO(make_overlay(width, height)))
    first.merge_page(overlay_reader.pages[0])
    writer.add_page(first)
    for page in reader.pages[1:]:
        writer.add_page(page)
    with OUT.open("wb") as f:
        writer.write(f)
    print(OUT)


if __name__ == "__main__":
    main()
