#!/usr/bin/env python3
"""pdf2note: turn a PDF into a BOOX Notes ".note" file.

The notebook it produces has one page per PDF page, each sized to the PDF
page's own shape (so a page made for the screen fills the screen), backed by
the unmodified PDF (so the PDF's own links keep working).

Import on the device by opening the .note file from the file manager; Notes
asks "Do you want to import notebook ...?" and creates a new notebook.

    python3 pdf2note.py input.pdf [-o output.note] [--title T] [--screen WxH]

Layout and field values follow a notebook that Notes 46343 (Palma 3,
firmware 4.3.1) wrote for its own "From Local File" PDF import. The only
deliberate differences are the page sizes (the PDF page's own shape at a width
of 2100, as Notes 45960 stored) and the saved zoom.
"""
import argparse
import json
import os
import struct
import sys
import time
import uuid
import zipfile

from pypdf import PdfReader

# Width of every page in Notes units (an A4 page is 2100 x 2970).
PAGE_WIDTH = 2100.0
NOTES_VERSION = 46343

PEN_INFO = (
    '{"eraseWidthMap":{},"fillColor":-16777216,"graphicsShapeColor":-16777216,'
    '"graphicsShapeType":7,"normalPenShapeType":5,"penLineStyle":{"lineStyle":'
    '{"phase":0.0,"type":0}},"penPressureSensitivityMap":{},"penSmoothLevelMap":{},'
    '"penWithMap":{0:5.905512,1:5.905512,2:5.905512,5:5.905512,7:5.905512,'
    '8:5.905512,15:59.05512,21:11.811024,22:3.543307,24:5.905512,25:5.905512,'
    '26:5.905512,27:5.905512,60:5.905512,28:5.905512,61:5.905512},'
    '"quickPenList":{"quickPens":[{"color":-16777216,"id":"1","type":5,'
    '"useTimeMs":0,"width":5.905512}],"selectedId":"1"},'
    '"shapeLineStyle":{"lineStyle":{"phase":0.0,"type":0}}}'
)
DOC_OPTIONS = (
    '{"alignCenterAfterOpen":false,"applyMarginToAll":true,'
    '"applyMarginToCover":false,"autoApplyPageMargin":true,'
    '"enabledAdaptiveStrokeWidth":true,"enabledContinuousPage":false,'
    '"fetchPolicy":1,"firstApplyMargin":true,"layoutType":1,'
    '"scrollerHorizontalGravity":8388613,"scrollerVerticalGravity":48,"version":0}'
)
LAYERS = '{"layerList":[{"id":0,"lock":false,"show":true}]}'
EMPTY_NAMES = '{"pageNameList":[]}'


# --- protobuf wire encoding (only what these records use) -------------------
def _varint(n):
    n &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while True:
        c = n & 0x7F
        n >>= 7
        if n:
            out.append(c | 0x80)
        else:
            out.append(c)
            return bytes(out)


def S(field, text):
    data = text if isinstance(text, bytes) else text.encode("utf-8")
    return _varint(field << 3 | 2) + _varint(len(data)) + data


def I(field, n):
    return _varint(field << 3) + _varint(n)


def F(field, x):
    return _varint(field << 3 | 5) + struct.pack("<f", x)


# --- helpers ----------------------------------------------------------------
def fnum(x):
    """Float the way the device writes it in JSON: always with a decimal."""
    return repr(float(x))


def rect(w, h):
    return ('{"bottom":%s,"empty":false,"left":0.0,"right":%s,"stability":0,"top":0.0}'
            % (fnum(h), fnum(w)))


def page_boxes(reader):
    """(width_pt, height_pt, object number, generation) per page, as displayed."""
    out = []
    for i, page in enumerate(reader.pages):
        box = page.cropbox
        w, h = float(box.width), float(box.height)
        if int(page.get("/Rotate", 0) or 0) % 180 == 90:
            w, h = h, w
        ref = page.indirect_reference
        if ref is None:
            sys.exit(f"page {i + 1} is not an indirect object; cannot reference it")
        out.append((w, h, ref.idnum, ref.generation))
    return out


def fit(w, h):
    """Page size in Notes units: the PDF page scaled to the standard width.

    This is what Notes 45960 stores for an imported PDF page.
    """
    return PAGE_WIDTH, round(h * PAGE_WIDTH / w, 3)


def build(pdf_path, out_path, title, screen):
    sw, sh = screen
    reader = PdfReader(pdf_path)
    if reader.is_encrypted:
        sys.exit("encrypted PDFs are not supported by Notes")
    pages = page_boxes(reader)
    if not pages:
        sys.exit("PDF has no pages")

    now = int(time.time() * 1000)
    note_id = str(uuid.uuid4())
    page_ids = [str(uuid.uuid4()) for _ in pages]
    pdf_name = os.path.basename(pdf_path)
    content_path = f"document/{note_id}/resource/content/{pdf_name}"
    template_name = f"{uuid.uuid4()}.template_json"
    sizes = [fit(w, h) for w, h, _, _ in pages]

    # virtual/page/pb: one VirtualPage per PDF page
    vpages = bytearray()
    for i, ((w, h, obj, gen), (pw, ph), pid) in enumerate(zip(pages, sizes, page_ids)):
        content_id = json.dumps(
            {"pageIndex": i, "pageReferenceId": f"pdf-{obj}-{gen}", "pageUUID": ""},
            separators=(",", ":"))
        rec = (S(1, pid) + I(2, now) + I(3, now) + F(4, float(i + 1))
               + S(6, rect(pw, ph)) + S(7, rect(pw, ph)) + S(8, rect(w, h))
               + S(9, "import_pdf") + S(10, content_path) + S(11, content_id))
        vpages += S(1, rec)

    # pageModel/pb: ink canvas record per page
    pmodels = bytearray()
    for pid in page_ids:
        pmodels += S(1, S(1, pid) + S(2, LAYERS) + I(5, now) + I(6, now) + S(7, rect(sw, sh)))

    # virtual/doc/pb: view state. Zoom = first page fitted to the screen.
    pw0, ph0 = sizes[0]
    zoom = min(sw / pw0, sh / ph0)
    default_template = json.dumps(
        {"contentId": "0", "contentPageId": "",
         "contentPageSize": "@RECT@",
         "contentRelativePath": f"template/json/{template_name}",
         "contentType": "geo_layout"},
        separators=(",", ":")).replace('"@RECT@"', rect(pw0, ph0))
    vdoc = (S(1, uuid.uuid4().hex) + I(2, now) + I(3, now) + S(4, page_ids[0])
            + F(5, zoom) + S(9, default_template) + S(10, DOC_OPTIONS))

    # note/pb/note_info: the notebook record
    page_info = ",".join(
        '"%s":{"currentLayerId":0,"height":%d,"lastModifyTime":0,'
        '"layerList":[{"id":0,"lock":false,"show":true}],"width":%d}' % (pid, sh, sw)
        for pid in page_ids)
    note_page_info = (
        '{"canvasExpandType":"DEFAULT","coverPageId":"","defaultPageRect":'
        '{"bottom":%d,"empty":false,"left":0,"right":%d,"stability":0,"top":0,"valid":true},'
        '"pageInfoMap":{%s},"zoomInfo":{"fitToScreen":false,"scaleType":0,'
        '"viewPortHeight":%s,"viewPortPos":{"isEmpty":false,"pressure":0.0,"size":0.0,'
        '"tiltX":0,"tiltY":0,"timestamp":0,"x":0.0,"y":0.0},"viewPortWidth":%s,'
        '"viewportScale":1.0}}' % (sh, sw, page_info, fnum(sh), fnum(sw)))
    background = (
        '{"bkGroundConfig":{"applyAllPage":false,"asDefault":true,'
        '"canvasAutoExpand":false,"scaleType":1},"docBKGround":{"bkGroundResRectF":%s,'
        '"cloud":false,"global":false,"height":%s,"resIndex":0,"type":0,"visible":true,'
        '"width":%s},"pageBKGroundMap":{},"useDocBKGround":false}'
        % (rect(sw, sh), fnum(sh), fnum(sw)))
    device = '{"deviceName":"pdf2note","size":{"height":%s,"width":%s}}' % (fnum(sh), fnum(sw))
    names = json.dumps({"pageNameList": page_ids}, separators=(",", ":"))
    note = (S(1, note_id) + I(2, now) + I(3, now) + S(6, title) + I(8, 1)
            + F(9, 5.905512) + F(10, 5.905512) + S(11, PEN_INFO)
            + S(12, note_page_info) + S(13, background) + S(14, device)
            + I(15, -16777216) + I(16, 5) + I(18, 2) + S(20, names)
            + S(21, EMPTY_NAMES) + F(22, float(sw)) + F(23, float(sh))
            + S(24, "Local") + I(31, 1) + I(37, 1) + I(40, 1) + I(43, 3)
            + S(44, EMPTY_NAMES))
    note_info = S(1, note)

    extra = I(1, 1) + I(2, NOTES_VERSION) + S(4, note_id)

    root = note_id + "/"
    dirs = ["note/", "note/pb/", "template/", "template/json/", "virtual/",
            "virtual/doc/", "virtual/doc/pb/", "virtual/page/", "virtual/page/pb/",
            "resource/", "resource/content/", "resource/pb/", "pageModel/",
            "pageModel/pb/", "extra/", "extra/pb/", "stash/", "stash/shape/",
            "stash/shape/resource/", "stash/archivedShape/"]
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(root, b"")
        for d in dirs:
            z.writestr(root + d, b"")
        z.writestr(root + "note/pb/note_info", note_info)
        z.writestr(root + "template/json/" + template_name, b"")
        z.writestr(root + "virtual/doc/pb/" + note_id, vdoc)
        z.writestr(root + "virtual/page/pb/" + str(uuid.uuid4()), bytes(vpages))
        z.writestr(root + "resource/pb/%s#%d" % (uuid.uuid4(), now), b"")
        z.writestr(root + "pageModel/pb/" + str(uuid.uuid4()), bytes(pmodels))
        z.writestr(root + "extra/pb/extra", extra)
        z.write(pdf_path, root + "resource/content/" + pdf_name)

    shapes = sorted({(round(w, 2), round(h, 2)) for w, h, _, _ in pages})
    print(f"{out_path}: {len(pages)} pages, PDF page size(s) in points: {shapes}")
    print(f"first page -> {pw0:g} x {ph0:g} Notes units, opening zoom {zoom:.4f} "
          f"for a {sw}x{sh} screen")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("pdf")
    ap.add_argument("-o", "--output")
    ap.add_argument("--title")
    ap.add_argument("--screen", default="824x1648",
                    help="target screen in pixels, WxH (default: Palma, 824x1648). "
                         "Sets the ink canvas record and the zoom the notebook "
                         "first opens at.")
    a = ap.parse_args()
    try:
        sw, sh = (int(x) for x in a.screen.lower().split("x"))
    except ValueError:
        ap.error("--screen must look like 824x1648")
    base = os.path.splitext(os.path.basename(a.pdf))[0]
    build(a.pdf, a.output or base + ".note", a.title or base, (sw, sh))


if __name__ == "__main__":
    main()
