#!/usr/bin/env python3
"""Write docs/sample/papermill-demo.pdf: a small 1:2 PDF with internal links.

Pages are 198 x 396 pt (the shape of a Palma screen) with a border at the very edge, so it
is easy to see whether a notebook shows the page full bleed. Standard library only.
"""
from pathlib import Path

W, H = 198, 396
SECTIONS = ["A", "B", "C", "D"]


def text(x, y, size, s, font="F1"):
    return f"BT /{font} {size} Tf {x} {y} Td ({s}) Tj ET\n"


def button(x, y, w, h, label, size=11):
    return (f"1 w {x} {y} {w} {h} re S\n"
            + text(x + (w - 0.5 * size * len(label)) / 2, y + (h - size) / 2 + 2, size, label, "F2"))


def frame(title, subtitle):
    return ("0 0 0 RG 0 0 0 rg\n"
            f"3 w 1.5 1.5 {W - 3} {H - 3} re S\n"          # border at the page edge
            f"0 {H - 64} {W} 64 re f\n"                    # header band
            "1 1 1 rg\n" + text(14, H - 40, 22, title, "F2") + text(14, H - 56, 8, subtitle)
            + "0 0 0 rg\n")


def build():
    # (content stream, [(rect, target page index)])
    pages = []
    cover = frame("Papermill", "DEMO NOTEBOOK") + text(14, 250, 10, "A 1:2 page with links.")
    cover += text(14, 234, 10, "The border should touch") + text(14, 220, 10, "all four screen edges.")
    cover += button(39, 40, 120, 34, "OPEN INDEX >")
    pages.append((cover, [((39, 40, 159, 74), 1)]))

    index = frame("Index", "JUMP TO A SECTION")
    links = []
    for i, name in enumerate(SECTIONS):
        y = 250 - i * 50
        index += button(39, y, 120, 34, f"SECTION {name}")
        links.append(((39, y, 159, y + 34), 2 + i))
    pages.append((index, links))

    for i, name in enumerate(SECTIONS):
        body = frame(name, f"SECTION {i + 1} OF {len(SECTIONS)}")
        for row in range(7):
            body += f"0.5 w 14 {280 - row * 28} m {W - 14} {280 - row * 28} l S\n"
        body += button(14, 40, 78, 30, "INDEX", 10)
        links = [((14, 40, 92, 70), 1)]
        if i + 1 < len(SECTIONS):
            body += button(106, 40, 78, 30, "NEXT >", 10)
            links.append(((106, 40, 184, 70), 3 + i))
        pages.append((body, links))

    objects = {}                      # number -> bytes
    count = len(pages)
    page_ids = [3 + i for i in range(count)]
    next_id = 3 + count
    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    kids = " ".join(f"{n} 0 R" for n in page_ids)
    objects[2] = f"<< /Type /Pages /Count {count} /Kids [ {kids} ] >>".encode()
    font1, font2 = next_id, next_id + 1
    next_id += 2
    objects[font1] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    objects[font2] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"
    for i, (content, links) in enumerate(pages):
        stream = content.encode("ascii")
        content_id = next_id
        next_id += 1
        objects[content_id] = b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"endstream"
        annots = []
        for (x0, y0, x1, y1), target in links:
            objects[next_id] = (f"<< /Type /Annot /Subtype /Link /Border [ 0 0 0 ] "
                                f"/Rect [ {x0} {y0} {x1} {y1} ] "
                                f"/Dest [ {page_ids[target]} 0 R /Fit ] >>").encode()
            annots.append(f"{next_id} 0 R")
            next_id += 1
        objects[page_ids[i]] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 {W} {H} ] "
            f"/Resources << /Font << /F1 {font1} 0 R /F2 {font2} 0 R >> >> "
            f"/Contents {content_id} 0 R /Annots [ {' '.join(annots)} ] >>").encode()

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for number in sorted(objects):
        offsets[number] = len(out)
        out += b"%d 0 obj\n" % number + objects[number] + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % next_id
    for number in range(1, next_id):
        out += b"%010d 00000 n \n" % offsets[number]
    out += (b"trailer\n<< /Size %d /Root 1 0 R /Info << /Title (Papermill demo) >> >>\n"
            b"startxref\n%d\n%%%%EOF\n" % (next_id, xref))
    return bytes(out)


if __name__ == "__main__":
    target = Path(__file__).resolve().parents[1] / "docs/sample/papermill-demo.pdf"
    target.write_bytes(build())
    print(target)
