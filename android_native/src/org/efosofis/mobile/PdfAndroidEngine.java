package org.efosofis.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Android yerel PdfDocument ve PdfRenderer tabanlı PDF oluşturma ve görüntüleme motoru.
 */
public final class PdfAndroidEngine {

    public static final class RenderedPage {
        public final Bitmap bitmap;
        public final int pageIndex;
        public final int totalPages;

        public RenderedPage(Bitmap bitmap, int pageIndex, int totalPages) {
            this.bitmap = bitmap;
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
        }
    }

    private PdfAndroidEngine() {}

    public static int createPdfFromText(File file, String title, String content) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        final int pageWidth = 595;   // A4 (72 dpi)
        final int pageHeight = 842;
        final float marginX = 48f;
        final float marginTop = 56f;
        final float marginBottom = 56f;
        final float maxTextWidth = pageWidth - marginX * 2f;

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.rgb(29, 78, 216));
        titlePaint.setTextSize(18f);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.rgb(15, 23, 42));
        bodyPaint.setTextSize(12f);
        bodyPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(Color.rgb(100, 116, 139));
        footerPaint.setTextSize(9f);

        List<String> wrappedLines = new ArrayList<String>();
        String safeContent = content == null ? "" : content;
        for (String rawLine : safeContent.split("\r?\n", -1)) {
            if (rawLine.trim().isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            String remaining = rawLine;
            while (!remaining.isEmpty()) {
                int count = bodyPaint.breakText(remaining, true, maxTextWidth, null);
                if (count <= 0) break;
                int splitAt = count;
                if (splitAt < remaining.length()) {
                    int lastSpace = remaining.substring(0, splitAt).lastIndexOf(' ');
                    if (lastSpace > 0) splitAt = lastSpace + 1;
                }
                wrappedLines.add(remaining.substring(0, splitAt).trim());
                remaining = remaining.substring(splitAt).trim();
            }
        }
        if (wrappedLines.isEmpty()) wrappedLines.add("");

        PdfDocument pdf = new PdfDocument();
        int pageNum = 1;
        int lineIdx = 0;
        try {
            while (lineIdx < wrappedLines.size() || pageNum == 1) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create();
                PdfDocument.Page page = pdf.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);

                float y = marginTop;
                if (pageNum == 1 && title != null && !title.trim().isEmpty()) {
                    canvas.drawText(title.trim(), marginX, y, titlePaint);
                    y += 28f;
                }

                float lineHeight = 18f;
                while (lineIdx < wrappedLines.size() && y + lineHeight <= pageHeight - marginBottom) {
                    canvas.drawText(wrappedLines.get(lineIdx), marginX, y, bodyPaint);
                    y += lineHeight;
                    lineIdx++;
                }

                canvas.drawText("EFOS-Ofis • Sayfa " + pageNum, marginX, pageHeight - 24f, footerPaint);
                pdf.finishPage(page);
                if (lineIdx >= wrappedLines.size()) break;
                pageNum++;
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                pdf.writeTo(fos);
            }
        } finally {
            pdf.close();
        }
        return pageNum;
    }

    public static RenderedPage renderPdfPage(File file, int pageIndex) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            int total = renderer.getPageCount();
            if (total <= 0) {
                throw new IllegalStateException("PDF dosyasında sayfa bulunamadı");
            }
            int safeIdx = Math.max(0, Math.min(pageIndex, total - 1));
            try (PdfRenderer.Page page = renderer.openPage(safeIdx)) {
                // En-boy oranını koruyarak ölçekle (çok büyük sayfalarda bellek sınırı)
                float scale = Math.min(2f, Math.min(1800f / page.getWidth(), 2546f / page.getHeight()));
                int w = Math.max(1, Math.round(page.getWidth() * scale));
                int h = Math.max(1, Math.round(page.getHeight() * scale));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.drawColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return new RenderedPage(bmp, safeIdx, total);
            }
        }
    }
}
