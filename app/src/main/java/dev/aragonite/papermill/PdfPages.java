package dev.aragonite.papermill;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSObject;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads what the notebook needs from a PDF: each page's displayed size and PDF object. */
final class PdfPages {
    static final class Unsupported extends IOException {
        Unsupported(String message) {
            super(message);
        }
    }

    private PdfPages() {
    }

    static List<NoteWriter.Page> read(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) {
                throw new Unsupported("encrypted");
            }
            COSBase tree = document.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.PAGES);
            List<NoteWriter.Page> pages = new ArrayList<>();
            if (tree instanceof COSDictionary) {
                walk((COSDictionary) tree, pages, new HashSet<>());
            }
            if (pages.isEmpty() || pages.size() != document.getNumberOfPages()) {
                throw new Unsupported("page tree");
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new Unsupported("encrypted");
        }
    }

    private static void walk(COSDictionary node, List<NoteWriter.Page> pages,
            Set<COSDictionary> seen) throws IOException {
        if (!seen.add(node)) {
            throw new Unsupported("page tree");
        }
        COSBase kids = node.getDictionaryObject(COSName.KIDS);
        if (!(kids instanceof COSArray)) {
            return;
        }
        COSArray array = (COSArray) kids;
        for (int i = 0; i < array.size(); i++) {
            // The array holds references; the reference's number is what Notes keys a page by.
            COSBase reference = array.get(i);
            if (!(reference instanceof COSObject)) {
                throw new Unsupported("page tree");
            }
            COSObject object = (COSObject) reference;
            COSBase target = object.getObject();
            if (!(target instanceof COSDictionary)) {
                throw new Unsupported("page tree");
            }
            COSDictionary dictionary = (COSDictionary) target;
            if (dictionary.containsKey(COSName.KIDS)) {
                walk(dictionary, pages, seen);
                continue;
            }
            PDPage page = new PDPage(dictionary);
            PDRectangle box = page.getCropBox();
            double width = box.getWidth();
            double height = box.getHeight();
            if (page.getRotation() % 180 != 0) {
                double swap = width;
                width = height;
                height = swap;
            }
            if (!(width > 0) || !(height > 0)) {
                throw new Unsupported("page size");
            }
            pages.add(new NoteWriter.Page(width, height, object.getObjectNumber(),
                    object.getGenerationNumber()));
        }
    }
}
