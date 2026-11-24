package dev.aragonite.papermill;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NoteWriterTest {
    private static final byte[] PDF = "%PDF-1.3 pretend".getBytes(StandardCharsets.US_ASCII);
    private static final long NOW = 1795498700095L;

    private Map<String, byte[]> files;
    private String root;

    @Before
    public void build() throws Exception {
        List<NoteWriter.Page> pages = Arrays.asList(
                new NoteWriter.Page(198, 396, 8, 0),      // 1:2, the Palma's shape
                new NoteWriter.Page(612, 792, 61, 0),     // US Letter
                new NoteWriter.Page(792, 612, 100, 2));   // landscape
        int[] next = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NoteWriter.write(out, pages, "Test Book", "Test Book.pdf",
                () -> new ByteArrayInputStream(PDF), 824, 1648, NOW,
                () -> String.format("00000000-0000-0000-0000-%012d", next[0]++));
        files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        root = "00000000-0000-0000-0000-000000000000/";
    }

    @Test
    public void everythingSitsUnderOneNotebookFolder() {
        for (String name : files.keySet()) {
            assertTrue(name, name.startsWith(root));
        }
        assertArrayEquals(PDF, files.get(root + "resource/content/Test Book.pdf"));
        assertTrue(files.containsKey(root + "note/pb/note_info"));
        assertTrue(files.containsKey(root + "extra/pb/extra"));
        assertTrue(files.containsKey(root + "stash/archivedShape/"));
    }

    @Test
    public void pagesTakeThePdfPagesOwnShape() {
        List<Map<Integer, Object>> pages = pageRecords();
        assertEquals(3, pages.size());

        Map<Integer, Object> first = pages.get(0);
        String palma = "{\"bottom\":4200.0,\"empty\":false,\"left\":0.0,\"right\":2100.0,"
                + "\"stability\":0,\"top\":0.0}";
        assertEquals(palma, first.get(6));
        assertEquals("the margin box must equal the page", palma, first.get(7));
        assertEquals("{\"bottom\":396.0,\"empty\":false,\"left\":0.0,\"right\":198.0,"
                + "\"stability\":0,\"top\":0.0}", first.get(8));
        assertEquals(1f, first.get(4));

        assertEquals("{\"bottom\":2717.647,\"empty\":false,\"left\":0.0,\"right\":2100.0,"
                + "\"stability\":0,\"top\":0.0}", pages.get(1).get(6));
        assertEquals("landscape pages are scaled to the same width",
                "{\"bottom\":1622.727,\"empty\":false,\"left\":0.0,\"right\":2100.0,"
                + "\"stability\":0,\"top\":0.0}", pages.get(2).get(6));
        assertEquals(3f, pages.get(2).get(4));
    }

    @Test
    public void pagesPointAtThePdfTheWayNotesWritesIt() {
        List<Map<Integer, Object>> pages = pageRecords();
        for (Map<Integer, Object> page : pages) {
            assertEquals("import_pdf", page.get(9));
            assertEquals("document/00000000-0000-0000-0000-000000000000/resource/content/"
                    + "Test Book.pdf", page.get(10));
        }
        // Notes matches link targets against these strings byte for byte.
        assertEquals("{\"pageIndex\":0,\"pageReferenceId\":\"pdf-8-0\",\"pageUUID\":\"\"}",
                pages.get(0).get(11));
        assertEquals("{\"pageIndex\":2,\"pageReferenceId\":\"pdf-100-2\",\"pageUUID\":\"\"}",
                pages.get(2).get(11));
    }

    @Test
    public void notebookRecordListsEveryPageAndNoOwner() {
        Map<Integer, Object> wrapper = decode(files.get(root + "note/pb/note_info"));
        Map<Integer, Object> note = decode((byte[]) raw(files.get(root + "note/pb/note_info")).get(0)[1]);
        assertEquals(1, wrapper.size());
        assertEquals("Test Book", note.get(6));
        List<Map<Integer, Object>> pages = pageRecords();
        StringBuilder names = new StringBuilder("{\"pageNameList\":[");
        for (int i = 0; i < pages.size(); i++) {
            names.append(i == 0 ? "" : ",").append('"').append(pages.get(i).get(1)).append('"');
        }
        assertEquals(names + "]}", note.get(20));
        assertEquals("pen colour is opaque black as a signed int", -16777216L, note.get(15));
        assertEquals(3L, note.get(43));
        assertFalse("no parent library id", note.containsKey(4));
        assertFalse("no account id", note.containsKey(39));
        assertFalse("no account id", decode(files.get(root + "extra/pb/extra")).containsKey(3));
    }

    @Test
    public void opensZoomedToFitTheFirstPage() {
        Map<Integer, Object> doc = decode(files.get(root + "virtual/doc/pb/"
                + "00000000-0000-0000-0000-000000000000"));
        assertEquals((float) (824 / 2100.0), (Float) doc.get(5), 1e-6);
        assertEquals(pageRecords().get(0).get(1), doc.get(4));
    }

    @Test
    public void inkCanvasRecordsMatchTheScreen() {
        byte[] models = null;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().startsWith(root + "pageModel/pb/") && file.getValue().length > 0) {
                models = file.getValue();
            }
        }
        List<Object[]> entries = raw(models);
        assertEquals(3, entries.size());
        Map<Integer, Object> model = decode((byte[]) entries.get(0)[1]);
        assertEquals(pageRecords().get(0).get(1), model.get(1));
        assertEquals("{\"bottom\":1648.0,\"empty\":false,\"left\":0.0,\"right\":824.0,"
                + "\"stability\":0,\"top\":0.0}", model.get(7));
    }

    private List<Map<Integer, Object>> pageRecords() {
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().startsWith(root + "virtual/page/pb/") && file.getValue().length > 0) {
                List<Map<Integer, Object>> pages = new ArrayList<>();
                for (Object[] entry : raw(file.getValue())) {
                    pages.add(decode((byte[]) entry[1]));
                }
                return pages;
            }
        }
        throw new AssertionError("no page list");
    }

    /** Fields in order as {number, value}: Long, Float or byte[]. */
    private static List<Object[]> raw(byte[] data) {
        List<Object[]> fields = new ArrayList<>();
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (in.hasRemaining()) {
            long key = varint(in);
            int field = (int) (key >>> 3);
            switch ((int) (key & 7)) {
                case 0:
                    fields.add(new Object[] {field, varint(in)});
                    break;
                case 2:
                    byte[] bytes = new byte[(int) varint(in)];
                    in.get(bytes);
                    fields.add(new Object[] {field, bytes});
                    break;
                case 5:
                    fields.add(new Object[] {field, in.getFloat()});
                    break;
                default:
                    throw new AssertionError("wire type " + (key & 7));
            }
        }
        return fields;
    }

    /** Fields by number, with length-delimited values read as text. */
    private static Map<Integer, Object> decode(byte[] data) {
        Map<Integer, Object> fields = new LinkedHashMap<>();
        for (Object[] field : raw(data)) {
            Object value = field[1] instanceof byte[]
                    ? new String((byte[]) field[1], StandardCharsets.UTF_8) : field[1];
            fields.put((Integer) field[0], value);
        }
        return fields;
    }

    private static long varint(ByteBuffer in) {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = in.get();
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
    }
}
