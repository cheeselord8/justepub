package com.justepub.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.LruCache;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Small EPUB 2/3 reader for the library and reading screen. */
final class EpubParser {
    private static final int MAX_PACKAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BOOK_TEXT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHAPTER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 40 * 1024 * 1024;
    private static final int MAX_DECODED_IMAGE_CACHE_BYTES = 12 * 1024 * 1024;
    private static final LruCache<String, Bitmap> DECODED_IMAGE_CACHE =
            new LruCache<String, Bitmap>(MAX_DECODED_IMAGE_CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private static final Pattern HIDDEN_BLOCKS = Pattern.compile(
            "(?is)<(script|style|head|svg|iframe|object)[^>]*>.*?</\\1\\s*>");
    private static final Pattern IMAGE_SOURCE = Pattern.compile(
            "(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*(['\"])(.*?)\\1");

    static final class Metadata {
        final String title;
        final String author;

        Metadata(String title, String author) {
            this.title = title;
            this.author = author;
        }
    }

    static final class BookContent {
        final Metadata metadata;
        final CharSequence text;

        BookContent(Metadata metadata, CharSequence text) {
            this.metadata = metadata;
            this.text = text;
        }
    }

    private static final class PackageInfo {
        String title;
        String author;
        final Map<String, String> manifest = new HashMap<>();
        final List<String> spine = new ArrayList<>();
    }

    private EpubParser() { }

    static Metadata readMetadata(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String packagePath = null;
        String fallbackPackage = null;
        try (InputStream source = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if ("META-INF/container.xml".equals(name)) {
                    String container = readEntry(zip, MAX_PACKAGE_BYTES);
                    packagePath = findPackagePath(container);
                } else if (name.toLowerCase().endsWith(".opf")) {
                    if (fallbackPackage == null) fallbackPackage = name;
                    if (packagePath == null || packagePath.equals(name)) {
                        PackageInfo info = parsePackage(readEntry(zip, MAX_PACKAGE_BYTES));
                        if (info.title != null || info.author != null) {
                            return new Metadata(info.title, info.author);
                        }
                    } else {
                        skipEntry(zip);
                    }
                } else {
                    skipEntry(zip);
                }
                zip.closeEntry();
            }
        }
        if (fallbackPackage != null) {
            return new Metadata(null, null);
        }
        throw new IllegalArgumentException("This file does not contain an EPUB package.");
    }

    static BookContent readBook(Context context, Uri uri) throws Exception {
        LinkedHashMap<String, String> documents = new LinkedHashMap<>();
        int totalBytes = 0;
        try (InputStream source = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String lower = name.toLowerCase();
                if (lower.endsWith(".opf") || lower.endsWith(".xhtml")
                        || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    byte[] bytes = readEntryBytes(zip, MAX_CHAPTER_BYTES);
                    totalBytes += bytes.length;
                    if (totalBytes > MAX_BOOK_TEXT_BYTES) {
                        throw new IllegalArgumentException("This EPUB is too large to open in JustEpub.");
                    }
                    documents.put(name, new String(bytes, StandardCharsets.UTF_8));
                } else {
                    skipEntry(zip);
                }
                zip.closeEntry();
            }
        }

        String packagePath = null;
        try (InputStream source = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "META-INF/container.xml".equals(entry.getName())) {
                    packagePath = findPackagePath(readEntry(zip, MAX_PACKAGE_BYTES));
                    break;
                }
                skipEntry(zip);
            }
        }
        if (packagePath == null) {
            for (String name : documents.keySet()) {
                if (name.toLowerCase().endsWith(".opf")) {
                    packagePath = name;
                    break;
                }
            }
        }

        PackageInfo packageInfo = packagePath == null ? new PackageInfo()
                : parsePackage(documents.get(packagePath));
        List<String> chapterPaths = new ArrayList<>();
        String base = packagePath == null ? "" : parentPath(packagePath);
        for (String id : packageInfo.spine) {
            String href = packageInfo.manifest.get(id);
            if (href == null) continue;
            String resolved = resolvePath(base, href);
            if (documents.containsKey(resolved)) chapterPaths.add(resolved);
        }
        if (chapterPaths.isEmpty()) {
            for (String name : documents.keySet()) {
                String lower = name.toLowerCase();
                if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    chapterPaths.add(name);
                }
            }
            Collections.sort(chapterPaths, Comparator.naturalOrder());
        }
        if (chapterPaths.isEmpty()) {
            throw new IllegalArgumentException("No readable chapters were found in this EPUB.");
        }

        Set<String> imagePaths = findImagePaths(chapterPaths, documents);
        Map<String, byte[]> images = readReferencedImages(context, uri, imagePaths);

        SpannableStringBuilder bookText = new SpannableStringBuilder();
        for (String path : chapterPaths) {
            String html = documents.get(path);
            if (html == null) continue;
            String safeHtml = HIDDEN_BLOCKS.matcher(html).replaceAll("");
            Html.ImageGetter imageGetter = source -> createImageDrawable(
                    context, images, uri.toString(), parentPath(path), source);
            Spanned chapter = android.os.Build.VERSION.SDK_INT >= 24
                    ? Html.fromHtml(safeHtml, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)
                    : Html.fromHtml(safeHtml, imageGetter, null);
            String plain = chapter.toString().trim();
            if (plain.isEmpty()) continue;
            if (bookText.length() > 0) bookText.append("\n\n\n");
            int offset = bookText.length();
            bookText.append(chapter);
            while (bookText.length() > offset && Character.isWhitespace(bookText.charAt(bookText.length() - 1))) {
                bookText.delete(bookText.length() - 1, bookText.length());
            }
            if (bookText.length() > MAX_BOOK_TEXT_BYTES) {
                throw new IllegalArgumentException("This EPUB contains too much text to display.");
            }
        }
        if (bookText.length() == 0) {
            throw new IllegalArgumentException("No readable text was found in this EPUB.");
        }
        Metadata metadata = new Metadata(packageInfo.title, packageInfo.author);
        return new BookContent(metadata, bookText);
    }

    private static Set<String> findImagePaths(List<String> chapterPaths, Map<String, String> documents) {
        Set<String> paths = new LinkedHashSet<>();
        for (String chapterPath : chapterPaths) {
            String html = documents.get(chapterPath);
            if (html == null) continue;
            java.util.regex.Matcher matcher = IMAGE_SOURCE.matcher(html);
            while (matcher.find()) {
                String source = matcher.group(2).trim();
                String lower = source.toLowerCase();
                if (source.isEmpty() || lower.startsWith("data:") || lower.startsWith("http:")
                        || lower.startsWith("https:") || lower.startsWith("//")) continue;
                String path = resolvePath(parentPath(chapterPath), source);
                if (isSupportedImage(path)) paths.add(path);
            }
        }
        return paths;
    }

    private static boolean isSupportedImage(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private static Map<String, byte[]> readReferencedImages(Context context, Uri uri,
                                                            Set<String> imagePaths) throws Exception {
        Map<String, byte[]> images = new HashMap<>();
        if (imagePaths.isEmpty()) return images;
        int totalBytes = 0;
        try (InputStream source = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!imagePaths.contains(entry.getName())
                        || totalBytes >= MAX_TOTAL_IMAGE_BYTES) {
                    skipEntry(zip);
                } else {
                    try {
                        byte[] bytes = readEntryBytes(zip, MAX_IMAGE_BYTES);
                        if (totalBytes + bytes.length <= MAX_TOTAL_IMAGE_BYTES) {
                            images.put(entry.getName(), bytes);
                            totalBytes += bytes.length;
                        }
                    } catch (IllegalArgumentException tooLarge) {
                        // Ignore an oversized illustration and continue with the rest of the book.
                        skipEntry(zip);
                    }
                }
                zip.closeEntry();
            }
        }
        return images;
    }

    private static Drawable createImageDrawable(Context context, Map<String, byte[]> images,
                                                String bookId, String chapterDirectory, String source) {
        if (source == null || source.startsWith("data:")) return null;
        String imagePath = resolvePath(chapterDirectory, source);
        byte[] bytes = images.get(imagePath);
        if (bytes == null || bytes.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        float density = context.getResources().getDisplayMetrics().density;
        int maxWidth = Math.max(1, context.getResources().getDisplayMetrics().widthPixels
                - Math.round(48 * density));
        int maxHeight = Math.max(1, context.getResources().getDisplayMetrics().heightPixels
                - Math.round(220 * density));
        float scale = Math.min(1f, Math.min(maxWidth / (float) bounds.outWidth,
                maxHeight / (float) bounds.outHeight));
        int displayWidth = Math.max(1, Math.round(bounds.outWidth * scale));
        int displayHeight = Math.max(1, Math.round(bounds.outHeight * scale));

        String cacheKey = bookId + "\u0000" + imagePath;
        Drawable drawable = new EpubImageDrawable(cacheKey, bytes, bounds.outWidth, bounds.outHeight);
        drawable.setBounds(0, 0, displayWidth, displayHeight);
        return drawable;
    }

    /** Decodes only when the image scrolls into view and keeps a small shared bitmap cache. */
    private static final class EpubImageDrawable extends Drawable {
        private final String cacheKey;
        private final byte[] bytes;
        private final int sourceWidth;
        private final int sourceHeight;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        EpubImageDrawable(String cacheKey, byte[] bytes, int sourceWidth, int sourceHeight) {
            this.cacheKey = cacheKey;
            this.bytes = bytes;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }

        @Override
        public void draw(Canvas canvas) {
            Bitmap bitmap = DECODED_IMAGE_CACHE.get(cacheKey);
            if (bitmap == null) {
                bitmap = decodeForDisplay();
                if (bitmap == null) return;
                DECODED_IMAGE_CACHE.put(cacheKey, bitmap);
            }
            Rect bounds = getBounds();
            canvas.drawBitmap(bitmap, null, bounds, paint);
        }

        private Bitmap decodeForDisplay() {
            Rect bounds = getBounds();
            int displayWidth = Math.max(1, bounds.width());
            int displayHeight = Math.max(1, bounds.height());
            float sampleRatio = Math.max(sourceWidth / (float) displayWidth,
                    sourceHeight / (float) displayHeight);
            int sampleSize = 1;
            while (sampleSize < sampleRatio && sampleSize < 64) sampleSize <<= 1;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, getBounds().width());
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, getBounds().height());
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static PackageInfo parsePackage(String xml) throws Exception {
        PackageInfo result = new PackageInfo();
        if (xml == null) return result;
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(new StringReader(xml));
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if ("title".equals(tag) && result.title == null) {
                result.title = parser.nextText().trim();
            } else if ("creator".equals(tag) && result.author == null) {
                result.author = parser.nextText().trim();
            } else if ("item".equals(tag)) {
                String id = parser.getAttributeValue(null, "id");
                String href = parser.getAttributeValue(null, "href");
                if (id != null && href != null) result.manifest.put(id, href);
            } else if ("itemref".equals(tag)) {
                String id = parser.getAttributeValue(null, "idref");
                if (id != null) result.spine.add(id);
            }
        }
        return result;
    }

    private static String findPackagePath(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(new StringReader(xml));
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "rootfile".equals(parser.getName())) {
                String path = parser.getAttributeValue(null, "full-path");
                if (path != null) return path;
            }
        }
        return null;
    }

    private static String resolvePath(String base, String href) {
        String path = href.split("[?#]", 2)[0];
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) { }
        ArrayList<String> parts = new ArrayList<>();
        String joined = (base.isEmpty() ? "" : base + "/") + path;
        for (String part : joined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.toString();
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private static String readEntry(ZipInputStream zip, int limit) throws Exception {
        return new String(readEntryBytes(zip, limit), StandardCharsets.UTF_8);
    }

    private static byte[] readEntryBytes(ZipInputStream zip, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = zip.read(buffer)) != -1) {
            if (out.size() + count > limit) {
                throw new IllegalArgumentException("An EPUB file inside this book is too large.");
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void skipEntry(ZipInputStream zip) throws Exception {
        byte[] buffer = new byte[8192];
        while (zip.read(buffer) != -1) { }
    }
}
