package dev.aragonite.papermill;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a BOOX Notes ".note" file: one notebook page per PDF page, each sized to the PDF
 * page's own shape and backed by the unmodified PDF.
 *
 * <p>The layout and constant values follow a notebook that Notes 46343 (firmware 4.3.1)
 * wrote for its own "From Local File" PDF import. The deliberate differences are the page
 * size (that version stores A4 for every page, which letterboxes anything that is not
 * A4-shaped; Notes 45960 stored the page's own shape, as this does) and the saved zoom. Records carry no account, library or device identifiers; the Notes
 * importer issues its own notebook and page ids.
 */
final class NoteWriter {
    /** One PDF page as displayed: size in points and the PDF object that defines it. */
    static final class Page {
        final double widthPt;
        final double heightPt;
        final long objectNumber;
        final int generation;

        Page(double widthPt, double heightPt, long objectNumber, int generation) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
            this.objectNumber = objectNumber;
            this.generation = generation;
        }
    }

    interface PdfSource {
        InputStream open() throws IOException;
    }

    /** Width of every page in Notes units (an A4 page is 2100 x 2970). */
    static final double PAGE_WIDTH = 2100;
    static final int NOTES_VERSION = 46343;

    private static final String PEN_INFO =
            "{\"eraseWidthMap\":{},\"fillColor\":-16777216,\"graphicsShapeColor\":-16777216,"
            + "\"graphicsShapeType\":7,\"normalPenShapeType\":5,\"penLineStyle\":{\"lineStyle\":"
            + "{\"phase\":0.0,\"type\":0}},\"penPressureSensitivityMap\":{},\"penSmoothLevelMap\":{},"
            + "\"penWithMap\":{0:5.905512,1:5.905512,2:5.905512,5:5.905512,7:5.905512,"
            + "8:5.905512,15:59.05512,21:11.811024,22:3.543307,24:5.905512,25:5.905512,"
            + "26:5.905512,27:5.905512,60:5.905512,28:5.905512,61:5.905512},"
            + "\"quickPenList\":{\"quickPens\":[{\"color\":-16777216,\"id\":\"1\",\"type\":5,"
            + "\"useTimeMs\":0,\"width\":5.905512}],\"selectedId\":\"1\"},"
            + "\"shapeLineStyle\":{\"lineStyle\":{\"phase\":0.0,\"type\":0}}}";
    private static final String DOC_OPTIONS =
            "{\"alignCenterAfterOpen\":false,\"applyMarginToAll\":true,"
            + "\"applyMarginToCover\":false,\"autoApplyPageMargin\":true,"
            + "\"enabledAdaptiveStrokeWidth\":true,\"enabledContinuousPage\":false,"
            + "\"fetchPolicy\":1,\"firstApplyMargin\":true,\"layoutType\":1,"
            + "\"scrollerHorizontalGravity\":8388613,\"scrollerVerticalGravity\":48,\"version\":0}";
    private static final String LAYERS = "{\"layerList\":[{\"id\":0,\"lock\":false,\"show\":true}]}";
    private static final String NO_PAGES = "{\"pageNameList\":[]}";
    private static final String[] FOLDERS = {
        "note/", "note/pb/", "template/", "template/json/", "virtual/", "virtual/doc/",
        "virtual/doc/pb/", "virtual/page/", "virtual/page/pb/", "resource/",
        "resource/content/", "resource/pb/", "pageModel/", "pageModel/pb/", "extra/",
        "extra/pb/", "stash/", "stash/shape/", "stash/shape/resource/", "stash/archivedShape/",
    };

    private NoteWriter() {
    }

    /**
     * Page size in Notes units: the PDF page scaled to the standard width. This is what
     * Notes 45960 stores for an imported PDF page, so pens and other tools are sized against
     * the page the way they are in a notebook Notes made itself.
     */
    static double[] fit(double widthPt, double heightPt) {
        return new double[] {PAGE_WIDTH, round3(heightPt * PAGE_WIDTH / widthPt)};
    }

    /**
     * @param screenWidth  shorter side of the target screen in pixels
     * @param screenHeight longer side of the target screen in pixels
     * @param uuids        source of fresh ids (injectable for tests)
     */
    static void write(OutputStream out, List<Page> pages, String title, String pdfFileName,
            PdfSource pdf, int screenWidth, int screenHeight, long now, Supplier<String> uuids)
            throws IOException {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("no pages");
        }
        String noteId = uuids.get();
        String contentPath = "document/" + noteId + "/resource/content/" + pdfFileName;
        String templateName = uuids.get() + ".template_json";
        List<String> pageIds = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            pageIds.add(uuids.get());
        }

        Proto virtualPages = new Proto();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            double[] size = fit(page.widthPt, page.heightPt);
            String pageRect = rect(size[0], size[1]);
            // Notes finds a link's target page by comparing this exact string.
            String pointer = "{\"pageIndex\":" + i + ",\"pageReferenceId\":\"pdf-"
                    + page.objectNumber + "-" + page.generation + "\",\"pageUUID\":\"\"}";
            virtualPages.bytes(1, new Proto()
                    .str(1, pageIds.get(i)).varint(2, now).varint(3, now).f32(4, i + 1)
                    .str(6, pageRect).str(7, pageRect)
                    .str(8, rect(page.widthPt, page.heightPt))
                    .str(9, "import_pdf").str(10, contentPath).str(11, pointer)
                    .build());
        }

        String screenRect = rect(screenWidth, screenHeight);
        Proto pageModels = new Proto();
        for (String pageId : pageIds) {
            pageModels.bytes(1, new Proto().str(1, pageId).str(2, LAYERS)
                    .varint(5, now).varint(6, now).str(7, screenRect).build());
        }

        double[] first = fit(pages.get(0).widthPt, pages.get(0).heightPt);
        // Zoom the notebook first opens at: the first page fitted to the screen.
        float zoom = (float) Math.min(screenWidth / first[0], screenHeight / first[1]);
        String defaultTemplate = "{\"contentId\":\"0\",\"contentPageId\":\"\",\"contentPageSize\":"
                + rect(first[0], first[1]) + ",\"contentRelativePath\":\"template/json/"
                + templateName + "\",\"contentType\":\"geo_layout\"}";
        byte[] virtualDoc = new Proto()
                .str(1, uuids.get().replace("-", "")).varint(2, now).varint(3, now)
                .str(4, pageIds.get(0)).f32(5, zoom).str(9, defaultTemplate).str(10, DOC_OPTIONS)
                .build();

        StringBuilder pageInfo = new StringBuilder();
        StringBuilder names = new StringBuilder("{\"pageNameList\":[");
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) {
                pageInfo.append(',');
                names.append(',');
            }
            pageInfo.append('"').append(pageIds.get(i)).append("\":{\"currentLayerId\":0,\"height\":")
                    .append(screenHeight).append(",\"lastModifyTime\":0,\"layerList\":"
                            + "[{\"id\":0,\"lock\":false,\"show\":true}],\"width\":")
                    .append(screenWidth).append('}');
            names.append('"').append(pageIds.get(i)).append('"');
        }
        names.append("]}");
        String notePageInfo = "{\"canvasExpandType\":\"DEFAULT\",\"coverPageId\":\"\","
                + "\"defaultPageRect\":{\"bottom\":" + screenHeight + ",\"empty\":false,\"left\":0,"
                + "\"right\":" + screenWidth + ",\"stability\":0,\"top\":0,\"valid\":true},"
                + "\"pageInfoMap\":{" + pageInfo + "},\"zoomInfo\":{\"fitToScreen\":false,"
                + "\"scaleType\":0,\"viewPortHeight\":" + num(screenHeight) + ",\"viewPortPos\":"
                + "{\"isEmpty\":false,\"pressure\":0.0,\"size\":0.0,\"tiltX\":0,\"tiltY\":0,"
                + "\"timestamp\":0,\"x\":0.0,\"y\":0.0},\"viewPortWidth\":" + num(screenWidth)
                + ",\"viewportScale\":1.0}}";
        String background = "{\"bkGroundConfig\":{\"applyAllPage\":false,\"asDefault\":true,"
                + "\"canvasAutoExpand\":false,\"scaleType\":1},\"docBKGround\":{\"bkGroundResRectF\":"
                + screenRect + ",\"cloud\":false,\"global\":false,\"height\":" + num(screenHeight)
                + ",\"resIndex\":0,\"type\":0,\"visible\":true,\"width\":" + num(screenWidth)
                + "},\"pageBKGroundMap\":{},\"useDocBKGround\":false}";
        String device = "{\"deviceName\":\"Papermill\",\"size\":{\"height\":" + num(screenHeight)
                + ",\"width\":" + num(screenWidth) + "}}";
        byte[] notebook = new Proto()
                .str(1, noteId).varint(2, now).varint(3, now).str(6, title).varint(8, 1)
                .f32(9, 5.905512f).f32(10, 5.905512f).str(11, PEN_INFO).str(12, notePageInfo)
                .str(13, background).str(14, device).varint(15, -16777216).varint(16, 5)
                .varint(18, 2).str(20, names.toString()).str(21, NO_PAGES)
                .f32(22, screenWidth).f32(23, screenHeight).str(24, "Local").varint(31, 1)
                .varint(37, 1).varint(40, 1).varint(43, 3).str(44, NO_PAGES)
                .build();
        byte[] noteInfo = new Proto().bytes(1, notebook).build();
        byte[] extra = new Proto().varint(1, 1).varint(2, NOTES_VERSION).str(4, noteId).build();

        String root = noteId + "/";
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, root, null);
            for (String folder : FOLDERS) {
                entry(zip, root + folder, null);
            }
            entry(zip, root + "note/pb/note_info", noteInfo);
            entry(zip, root + "template/json/" + templateName, new byte[0]);
            entry(zip, root + "virtual/doc/pb/" + noteId, virtualDoc);
            entry(zip, root + "virtual/page/pb/" + uuids.get(), virtualPages.build());
            entry(zip, root + "resource/pb/" + uuids.get() + "#" + now, new byte[0]);
            entry(zip, root + "pageModel/pb/" + uuids.get(), pageModels.build());
            entry(zip, root + "extra/pb/extra", extra);
            zip.putNextEntry(new ZipEntry(root + "resource/content/" + pdfFileName));
            try (InputStream in = pdf.open()) {
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    zip.write(buffer, 0, n);
                }
            }
            zip.closeEntry();
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (data != null) {
            zip.write(data);
        }
        zip.closeEntry();
    }

    /** A rectangle at the origin, in the JSON form Notes writes. */
    static String rect(double width, double height) {
        return "{\"bottom\":" + num(height) + ",\"empty\":false,\"left\":0.0,\"right\":"
                + num(width) + ",\"stability\":0,\"top\":0.0}";
    }

    private static String num(double value) {
        return Double.toString(value);
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
