package com.justepub.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_EPUBS = 42;
    private static final int BG = Color.rgb(8, 8, 8);
    private static final int SURFACE = Color.rgb(18, 18, 18);
    private static final int LINE = Color.rgb(43, 43, 43);
    private static final int PRIMARY = Color.rgb(244, 244, 240);
    private static final int SECONDARY = Color.rgb(155, 155, 151);
    private static final int ACCENT = Color.rgb(214, 231, 222);
    private static final String PREFS = "justepub_library";
    private static final String BOOKS_KEY = "books";

    private final ArrayList<Book> books = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private LinearLayout root;
    private Book currentBook;
    private ScrollView readerScroll;
    private TextView readerText;
    private int fontSize = 20;
    private boolean readerOpen;

    private static final class Book {
        String uri;
        String fileName;
        String title;
        String author;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.setSoftInputMode(Window.SOFT_INPUT_ADJUST_NOTHING);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        fontSize = preferences.getInt("font_size", 20);
        loadBooks();
        showLibrary();
    }

    @Override
    protected void onPause() {
        saveReadingPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (readerOpen) {
            showLibrary();
        } else {
            super.onBackPressed();
        }
    }

    private void showLibrary() {
        saveReadingPosition();
        readerOpen = false;
        currentBook = null;
        root = makeRoot();
        setContentView(root);

        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleColumn = column();
        TextView title = label("JustEpub", 27, PRIMARY, true);
        TextView subtitle = label("Your books, ready to read.", 14, SECONDARY, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        titleColumn.addView(title);
        titleColumn.addView(subtitle);
        heading.addView(titleColumn, new LinearLayout.LayoutParams(0, -2, 1));

        Button add = button("＋  Add EPUB", true);
        add.setContentDescription("Add EPUB books");
        add.setOnClickListener(v -> chooseEpubs());
        heading.addView(add, wrap());
        root.addView(heading, params(-1, -2));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
        dividerParams.topMargin = dp(24);
        root.addView(divider, dividerParams);

        if (books.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView mark = label("Aa", 34, ACCENT, true);
            mark.setGravity(Gravity.CENTER);
            TextView emptyTitle = label("Your library is empty", 18, PRIMARY, true);
            TextView hint = label("Add an EPUB from your device to begin.", 14, SECONDARY, false);
            hint.setGravity(Gravity.CENTER);
            empty.addView(mark, params(-1, -2));
            LinearLayout.LayoutParams emptyTitleParams = params(-1, -2);
            emptyTitleParams.topMargin = dp(18);
            empty.addView(emptyTitle, emptyTitleParams);
            LinearLayout.LayoutParams hintParams = params(-1, -2);
            hintParams.topMargin = dp(8);
            empty.addView(hint, hintParams);
            Button emptyAdd = button("Choose a book", true);
            emptyAdd.setOnClickListener(v -> chooseEpubs());
            LinearLayout.LayoutParams emptyButtonParams = wrap();
            emptyButtonParams.topMargin = dp(24);
            empty.addView(emptyAdd, emptyButtonParams);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, 0, 1);
            root.addView(empty, emptyParams);
        } else {
            ScrollView list = new ScrollView(this);
            list.setFillViewport(false);
            LinearLayout rows = column();
            rows.setPadding(0, dp(8), 0, dp(16));
            for (int i = 0; i < books.size(); i++) {
                addBookRow(rows, books.get(i), i);
            }
            list.addView(rows);
            root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private void addBookRow(LinearLayout container, Book book, int index) {
        LinearLayout item = column();
        item.setPadding(dp(2), dp(17), dp(2), dp(17));
        TextView title = label(book.title, 17, PRIMARY, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        String detail = !TextUtils.isEmpty(book.author)
                ? "by " + book.author
                : book.fileName + "  ·  EPUB";
        TextView subtitle = label(detail, 13, SECONDARY, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(title);
        LinearLayout.LayoutParams detailParams = params(-1, -2);
        detailParams.topMargin = dp(6);
        item.addView(subtitle, detailParams);
        item.setOnClickListener(v -> openBook(book));
        item.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Remove this book?")
                    .setMessage(book.title + " will be removed from your JustEpub library.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        books.remove(book);
                        try {
                            getContentResolver().releasePersistableUriPermission(
                                    Uri.parse(book.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) { }
                        preferences.edit().remove(progressKey(book)).apply();
                        saveBooks();
                        showLibrary();
                    })
                    .show();
            return true;
        });
        container.addView(item, params(-1, -2));
        if (index < books.size() - 1) {
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            container.addView(divider, params(-1, dp(1)));
        }
    }

    private void openBook(Book book) {
        currentBook = book;
        readerOpen = true;
        root = makeRoot();
        setContentView(root);
        root.addView(label("Opening book…", 16, SECONDARY, false), params(-1, -2));
        Uri uri = Uri.parse(book.uri);
        worker.execute(() -> {
            try {
                EpubParser.BookContent content = EpubParser.readBook(this, uri);
                runOnUiThread(() -> {
                    if (isFinishing() || currentBook != book) return;
                    showReader(book, content.text);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || currentBook != book) return;
                    showLibrary();
                    showError("Could not open this EPUB", readableError(error));
                });
            }
        });
    }

    private void showReader(Book book, CharSequence content) {
        readerOpen = true;
        root = makeRoot();
        setContentView(root);

        LinearLayout backRow = row();
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        Button back = textButton("‹", "Back to library");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        back.setOnClickListener(v -> showLibrary());
        backRow.addView(back, params(dp(48), dp(48)));
        TextView heading = label("READING", 11, SECONDARY, true);
        heading.setLetterSpacing(0.12f);
        LinearLayout headingColumn = column();
        TextView bookTitle = label(book.title, 16, PRIMARY, true);
        bookTitle.setMaxLines(1);
        bookTitle.setEllipsize(TextUtils.TruncateAt.END);
        headingColumn.addView(heading);
        headingColumn.addView(bookTitle, params(-1, -2));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = dp(8);
        backRow.addView(headingColumn, headingParams);
        root.addView(backRow, params(-1, -2));

        LinearLayout sizeRow = row();
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel = label("Text size", 13, SECONDARY, false);
        sizeRow.addView(sizeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button smaller = textButton("−", "Decrease text size");
        smaller.setOnClickListener(v -> changeFontSize(-1));
        Button size = textButton(fontSize + "", "Current text size");
        size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        size.setTextColor(SECONDARY);
        Button larger = textButton("+", "Increase text size");
        larger.setOnClickListener(v -> changeFontSize(1));
        sizeRow.addView(smaller, params(dp(44), dp(42)));
        sizeRow.addView(size, params(dp(42), dp(42)));
        sizeRow.addView(larger, params(dp(44), dp(42)));
        LinearLayout.LayoutParams sizeParams = params(-1, -2);
        sizeParams.topMargin = dp(10);
        root.addView(sizeRow, sizeParams);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
        dividerParams.topMargin = dp(4);
        root.addView(divider, dividerParams);

        readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(false);
        readerScroll.setClipToPadding(false);
        readerText = label(content, fontSize, Color.rgb(222, 222, 216), false);
        readerText.setTypeface(Typeface.SERIF);
        readerText.setLineSpacing(dp(7), 1.08f);
        readerText.setTextIsSelectable(true);
        readerText.setPadding(dp(1), dp(22), dp(1), dp(52));
        readerScroll.addView(readerText, params(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(2);
        root.addView(readerScroll, scrollParams);
        int savedPosition = preferences.getInt(progressKey(book), 0);
        readerScroll.post(() -> readerScroll.scrollTo(0, savedPosition));

        // Keep the current size visible and update it when the reader controls change.
        size.setTag("reader-size-label");
    }

    private void changeFontSize(int amount) {
        fontSize = Math.max(14, Math.min(34, fontSize + amount));
        preferences.edit().putInt("font_size", fontSize).apply();
        if (readerText != null) {
            readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
            updateSizeLabel();
        }
    }

    private void updateSizeLabel() {
        if (root == null) return;
        Button size = findSizeButton(root);
        if (size != null) size.setText(String.valueOf(fontSize));
    }

    private Button findSizeButton(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button && "reader-size-label".equals(child.getTag())) {
                return (Button) child;
            }
            if (child instanceof LinearLayout) {
                Button found = findSizeButton((LinearLayout) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void chooseEpubs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/zip"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_EPUBS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EPUBS || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        if (selected.isEmpty()) return;
        int grantFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        for (Uri uri : selected) {
            try {
                getContentResolver().takePersistableUriPermission(uri, grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
        importBooks(selected);
    }

    private void importBooks(List<Uri> selected) {
        Set<String> existing = new HashSet<>();
        for (Book book : books) existing.add(book.uri);
        ArrayList<Uri> newUris = new ArrayList<>();
        for (Uri uri : selected) {
            if (existing.add(uri.toString())) newUris.add(uri);
        }
        if (newUris.isEmpty()) {
            Toast.makeText(this, "Those books are already in your library.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Adding " + newUris.size() + (newUris.size() == 1 ? " book…" : " books…"), Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            ArrayList<Book> imported = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();
            for (Uri uri : newUris) {
                String fileName = displayName(uri);
                String mime = getContentResolver().getType(uri);
                if (!fileName.toLowerCase().endsWith(".epub")
                        && (mime == null || !mime.equalsIgnoreCase("application/epub+zip"))) {
                    errors.add(fileName + " is not an EPUB file.");
                    continue;
                }
                try {
                    EpubParser.Metadata metadata = EpubParser.readMetadata(this, uri);
                    Book book = new Book();
                    book.uri = uri.toString();
                    book.fileName = fileName;
                    book.title = TextUtils.isEmpty(metadata.title) ? baseName(fileName) : metadata.title;
                    book.author = metadata.author;
                    imported.add(book);
                } catch (Exception error) {
                    errors.add(fileName + ": " + readableError(error));
                }
            }
            runOnUiThread(() -> {
                books.addAll(0, imported);
                saveBooks();
                showLibrary();
                if (!errors.isEmpty()) {
                    String message = TextUtils.join("\n", errors);
                    showError("Some books could not be added", message);
                }
            });
        });
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        if (TextUtils.isEmpty(name)) name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? "Untitled.epub" : name;
    }

    private String baseName(String name) {
        if (name == null) return "Untitled book";
        return name.replaceFirst("(?i)\\.epub$", "");
    }

    private void loadBooks() {
        books.clear();
        try {
            JSONArray array = new JSONArray(preferences.getString(BOOKS_KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Book book = new Book();
                book.uri = json.optString("uri");
                book.fileName = json.optString("fileName");
                book.title = json.optString("title");
                book.author = json.optString("author");
                if (!TextUtils.isEmpty(book.uri) && !TextUtils.isEmpty(book.title)) books.add(book);
            }
        } catch (Exception ignored) { }
    }

    private void saveBooks() {
        JSONArray array = new JSONArray();
        for (Book book : books) {
            JSONObject json = new JSONObject();
            try {
                json.put("uri", book.uri);
                json.put("fileName", book.fileName);
                json.put("title", book.title);
                json.put("author", book.author == null ? "" : book.author);
                array.put(json);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(BOOKS_KEY, array.toString()).apply();
    }

    private void saveReadingPosition() {
        if (readerOpen && currentBook != null && readerScroll != null) {
            preferences.edit().putInt(progressKey(currentBook), readerScroll.getScrollY()).apply();
        }
    }

    private String progressKey(Book book) {
        return "position:" + book.uri;
    }

    private LinearLayout makeRoot() {
        LinearLayout layout = column();
        layout.setBackgroundColor(BG);
        int horizontal = dp(22);
        layout.setPadding(horizontal, 0, horizontal, 0);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            int left = 0;
            int right = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            layout.setPadding(horizontal + left, top, horizontal + right, bottom);
            return insets;
        });
        layout.requestApplyInsets();
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView label(CharSequence text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String text, boolean filled) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setMinHeight(dp(44));
        view.setMinWidth(0);
        view.setPadding(dp(15), 0, dp(15), 0);
        view.setTextColor(filled ? BG : PRIMARY);
        view.setBackground(background(filled ? ACCENT : SURFACE, filled ? ACCENT : LINE, 12));
        return view;
    }

    private Button textButton(String text, String description) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextColor(PRIMARY);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setPadding(0, 0, 0, 0);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(background(SURFACE, LINE, 10));
        return view;
    }

    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String readableError(Exception error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? "The file could not be read." : message;
    }
}
