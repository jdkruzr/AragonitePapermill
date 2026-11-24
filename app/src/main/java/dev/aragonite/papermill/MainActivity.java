package dev.aragonite.papermill;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pick a PDF, make a .note from it, hand it to Notes. */
public final class MainActivity extends Activity {
    private static final int PICK_PDF = 1;
    /** Where finished notebooks go, under the shared Download folder. */
    private static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Papermill/";
    private static final String NOTES_PACKAGE = "com.onyx.android.note";
    private static final String NOTES_IMPORT = "com.onyx.android.note.note.ui.ImportNoteActivity";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Button choose;
    private Button make;
    private Button open;
    private TextView summary;
    private TextView nameLabel;
    private TextView status;
    private EditText name;

    private File pdfFile;
    private List<NoteWriter.Page> pages;
    private Uri noteUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());
        setContentView(R.layout.activity_main);
        choose = findViewById(R.id.choose);
        make = findViewById(R.id.make);
        open = findViewById(R.id.open);
        summary = findViewById(R.id.summary);
        nameLabel = findViewById(R.id.name_label);
        status = findViewById(R.id.status);
        name = findViewById(R.id.name);

        choose.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
            startActivityForResult(pick, PICK_PDF);
        });
        make.setOnClickListener(v -> makeNotebook());
        open.setOnClickListener(v -> openInNotes());
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /** A PDF opened or shared into the app from somewhere else. */
    private void handleIncoming(Intent intent) {
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            loadPdf(uri);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request == PICK_PDF) {
            loadPdf(data.getData());
        }
    }

    private void loadPdf(Uri uri) {
        String displayName = displayName(uri);
        pages = null;
        noteUri = null;
        show(make, false);
        show(open, false);
        show(name, false);
        show(nameLabel, false);
        show(status, false);
        summary.setText(getString(R.string.reading, displayName));
        show(summary, true);
        choose.setEnabled(false);
        worker.execute(() -> {
            File copy = new File(getCacheDir(), "source.pdf");
            int error = 0;
            List<NoteWriter.Page> read = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(copy)) {
                if (in == null) {
                    throw new IOException("no stream");
                }
                copy(in, out);
                read = PdfPages.read(copy);
            } catch (PdfPages.Unsupported e) {
                error = "encrypted".equals(e.getMessage())
                        ? R.string.error_encrypted : R.string.error_structure;
            } catch (IOException | RuntimeException e) {
                error = R.string.error_read;
            }
            int shownError = error;
            List<NoteWriter.Page> result = read;
            runOnUiThread(() -> {
                choose.setEnabled(true);
                if (shownError != 0) {
                    summary.setText(shownError);
                    return;
                }
                pdfFile = copy;
                pages = result;
                summary.setText(getString(R.string.pdf_summary, displayName, result.size(),
                        describe(result)));
                name.setText(stripExtension(displayName));
                show(nameLabel, true);
                show(name, true);
                show(make, true);
            });
        });
    }

    /** Page size, plus how that shape will sit on this screen. */
    private String describe(List<NoteWriter.Page> list) {
        NoteWriter.Page first = list.get(0);
        boolean uniform = true;
        for (NoteWriter.Page page : list) {
            if (Math.abs(page.widthPt - first.widthPt) > 0.5
                    || Math.abs(page.heightPt - first.heightPt) > 0.5) {
                uniform = false;
                break;
            }
        }
        if (!uniform) {
            return getString(R.string.page_size_mixed);
        }
        int[] screen = screen();
        double pageShape = first.widthPt / first.heightPt;
        double screenShape = (double) screen[0] / screen[1];
        int fit = Math.abs(pageShape - screenShape) < 0.01 ? R.string.shape_fills
                : pageShape < screenShape ? R.string.shape_bars_sides : R.string.shape_bars_ends;
        return getString(R.string.page_size_one, trim(first.widthPt), trim(first.heightPt))
                + "\n" + getString(fit);
    }

    private void makeNotebook() {
        String title = name.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (title.isEmpty()) {
            status.setText(R.string.error_name);
            show(status, true);
            return;
        }
        int[] screen = screen();
        List<NoteWriter.Page> list = pages;
        File source = pdfFile;
        show(open, false);
        status.setText(R.string.making);
        show(status, true);
        make.setEnabled(false);
        choose.setEnabled(false);
        worker.execute(() -> {
            String fileName = title + ".note";
            Uri saved = null;
            String failure = null;
            String savedName = fileName;
            long size = 0;
            try {
                saved = createDownload(fileName);
                try (OutputStream out = getContentResolver().openOutputStream(saved)) {
                    if (out == null) {
                        throw new FileNotFoundException(fileName);
                    }
                    NoteWriter.write(out, list, title, title + ".pdf",
                            () -> new FileInputStream(source), screen[0], screen[1],
                            System.currentTimeMillis(), () -> UUID.randomUUID().toString());
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(saved, done, null, null);
                // The system may have numbered the file if another app's file had the name.
                try (Cursor cursor = getContentResolver().query(saved, new String[] {
                        OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        savedName = cursor.getString(0);
                        size = cursor.getLong(1);
                    }
                }
            } catch (IOException | RuntimeException e) {
                failure = String.valueOf(e.getMessage());
                if (saved != null) {
                    getContentResolver().delete(saved, null, null);
                }
            }
            String shownFailure = failure;
            Uri result = saved;
            long shownSize = size;
            String shownName = savedName;
            runOnUiThread(() -> {
                make.setEnabled(true);
                choose.setEnabled(true);
                if (shownFailure != null) {
                    status.setText(getString(R.string.error_write, shownFailure));
                    return;
                }
                noteUri = result;
                status.setText(getString(R.string.made, shownName,
                        Formatter.formatShortFileSize(this, shownSize)));
                show(open, true);
            });
        });
    }

    /**
     * A new, still-hidden file in Download/Papermill. Notes names the imported notebook after
     * the file, so an earlier file of ours with the same name is replaced rather than left for
     * the system to number the new one.
     */
    private Uri createDownload(String fileName) throws IOException {
        ContentResolver resolver = getContentResolver();
        Uri downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        resolver.delete(downloads, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[] {FOLDER, fileName});
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri created = resolver.insert(downloads, values);
        if (created == null) {
            throw new IOException("Download folder unavailable");
        }
        return created;
    }

    /**
     * Same as tapping the file in the file manager: Notes asks whether to import it. Notes
     * needs a link it can turn into a real file path, which a shared-storage file has.
     */
    private void openInNotes() {
        Uri file = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                Long.parseLong(noteUri.getLastPathSegment()));
        Intent view = new Intent(Intent.ACTION_VIEW).setData(file)
                .setComponent(new ComponentName(NOTES_PACKAGE, NOTES_IMPORT))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText(R.string.error_no_notes);
        }
    }

    /** Shorter and longer side of the whole display, in pixels. */
    private int[] screen() {
        Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        return new int[] {Math.min(bounds.width(), bounds.height()),
                Math.max(bounds.width(), bounds.height())};
    }

    private String displayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            } catch (RuntimeException ignored) {
                // Fall through to the path.
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "PDF" : last.substring(last.lastIndexOf('/') + 1);
    }

    private static String stripExtension(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private static String trim(double points) {
        return points == Math.rint(points) ? String.valueOf((long) points)
                : String.format(Locale.ROOT, "%.1f", points);
    }

    private static void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
    }
}
