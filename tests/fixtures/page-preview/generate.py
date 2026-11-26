"""Original three-chapter EPUB; no downloaded book or font payload."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED

target = Path(__file__).resolve().parent / 'data/page-probe.epub'
target.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(target, 'w', compression=ZIP_DEFLATED) as z:
    z.writestr('mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
    z.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    z.writestr('book.opf', '''<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">jingdu-page-probe-v1</dc:identifier><dc:title>翻页捕获验证</dc:title><dc:language>zh-CN</dc:language><meta property="dcterms:modified">2026-11-26T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="image" href="image.svg" media-type="image/svg+xml"/>''' + ''.join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(3)) + '</manifest><spine>' + ''.join(f'<itemref idref="c{i}"/>' for i in range(3)) + '</spine></package>')
    z.writestr('nav.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>' + ''.join(f'<li><a href="c{i}.xhtml">第{i+1}章</a></li>' for i in range(3)) + '</ol></nav></body></html>')
    z.writestr('image.svg', '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="120"><rect width="480" height="120" fill="#2b7a78"/><circle cx="90" cy="60" r="45" fill="#ffd166"/><path d="M170 100L250 20L330 100" stroke="white" stroke-width="8" fill="none"/></svg>')
    for i in range(3):
        body = f'<h1>第{i+1}章 捕获与位置</h1><img src="image.svg" style="width:80%;height:auto"/>'
        body += ''.join(f'<p>章{i+1}段{j:03d}：静读翻页验证。风吹过窗台，树影落在纸上。字体、图片、页边距与主题应当保持一致。ABC 123，标点测试。</p>' for j in range(72))
        z.writestr(f'c{i}.xhtml', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>验证章节</title></head><body>' + body + '</body></html>')
print(target)
with ZipFile(target) as original, ZipFile(target.with_name('page-probe-alt.epub'), 'w', compression=ZIP_DEFLATED) as alternate:
    for item in original.infolist():
        contents = original.read(item.filename)
        if item.filename.endswith(('.xhtml', '.opf')):
            contents = contents.decode().replace('静读翻页验证', '备用书籍验证').replace('jingdu-page-probe-v1', 'jingdu-page-probe-alt-v1').encode()
        alternate.writestr(item, contents)
