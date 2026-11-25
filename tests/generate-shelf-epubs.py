"""Generate a deterministic original EPUB corpus for shelf performance tests."""
import argparse
from html import escape
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

CONTAINER = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""


def book(path, number):
    identifier = f"jingdu-perf-{number:04d}"
    title = f"纸间观测笔记 {number:04d}"
    author = f"静读样例作者 {number % 29 + 1:02d}"
    opf = f"""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">{identifier}</dc:identifier><dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator><dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-11-26T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="body" href="body.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="body"/></spine>
</package>"""
    paragraph = f"我在第 {number} 站记下天气和树影。一阵风掠过窗台，纸页在茶香里缓缓翻过。"
    nav = """<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops">
      <ol><li><a href="body.xhtml">第一章 纸上微光</a></li></ol></nav>"""
    def xhtml(body):
        return f'<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN"><head><title>{title}</title></head><body>{body}</body></html>'

    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
        archive.writestr("META-INF/container.xml", CONTAINER)
        archive.writestr("book.opf", opf)
        archive.writestr("nav.xhtml", xhtml(nav))
        archive.writestr("body.xhtml", xhtml("<h1>第一章 纸上微光</h1>" + f"<p>{escape(paragraph)}</p>" * 6))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--counts", type=int, nargs="+", choices=(100, 500, 1000), default=[100, 500, 1000])
    options = parser.parse_args()
    for count in options.counts:
        folder = options.output / f"epub-{count}"
        folder.mkdir(parents=True, exist_ok=True)
        for number in range(1, count + 1):
            book(folder / f"jingdu-perf-{number:04d}.epub", number)
        print(f"{count}: {folder}")


if __name__ == "__main__":
    main()
