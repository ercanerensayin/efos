package org.efosofis.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
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
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EFOS-Ofis Android — Belge (.docx), Tablo (.xlsx/.csv + Formül), Sunu (.pptx),
 * PDF Okuyucu/Oluşturucu ve Güvenli ZIP.
 *
 * <p>Dosya işlemleri arka plan iş parçacığında yapılır (arayüz donmaz). Telefondan açılan
 * dosyalar kaydedilince asıl konumlarına da yazılır; "Telefona Kaydet" ve "Paylaş" ile
 * belgeler çalışma klasörünün dışına çıkarılabilir.</p>
 */
public class MainActivity extends Activity {

    static final String APP_VERSION = "2.2";

    private static final int REQ_OPEN_DOCUMENT = 1001;
    private static final int REQ_EXPORT_DOCUMENT = 1002;
    private static final int MIN_ROWS = 12;
    private static final int MIN_COLS = 5;
    private static final int MAX_ROWS = 150;
    private static final int MAX_COLS = 12;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private File workDir;
    private boolean darkMode = false;
    private int activeTab = 0;

    private LinearLayout rootLayout;
    private LinearLayout topBar;
    private FrameLayout contentContainer;
    private TextView statusBar;
    private LinearLayout navBar;
    private final List<Button> navButtons = new ArrayList<Button>();

    // Telefondan (SAF) açılan dosyaların asıl adresleri: yerel kopya -> kaynak Uri
    private final Map<String, Uri> externalSources = new HashMap<String, Uri>();
    private File pendingExport;

    // Belge durumu
    private EditText docNameInput;
    private EditText docEditor;
    private File docSource;

    // Tablo durumu
    private EditText sheetNameInput;
    private TextView sheetFormulaBanner;
    private Button sheetCalcBtn;
    private LinearLayout sheetGrid;
    private final Map<String, String> sheetRawCells = new HashMap<String, String>();
    private EditText[][] sheetInputs = new EditText[0][0];
    private int sheetRows = MIN_ROWS;
    private int sheetCols = MIN_COLS;
    private boolean sheetShowEvaluated = false;
    private File sheetSource;

    // Sunu durumu
    private EditText slideNameInput;
    private TextView slidePreviewTitle;
    private TextView slidePreviewBody;
    private TextView slidePreviewNum;
    private TextView slideCounterLbl;
    private EditText slideTitleInput;
    private EditText slideBodyInput;
    private final List<OfficeEngines.SlideItem> slides = new ArrayList<OfficeEngines.SlideItem>();
    private int slideIndex = 0;
    private File slideSource;

    // PDF durumu
    private EditText pdfNameInput;
    private EditText pdfTextInput;
    private TextView pdfPageLbl;
    private ImageView pdfImageView;
    private int pdfPageIndex = 0;
    private int pdfTotalPages = 0;

    // ZIP durumu
    private EditText zipNameInput;
    private TextView zipLogView;

    private final View[] tabViews = new View[5];

    // ==================== Arka plan işleri ====================

    interface Job {
        Object run() throws Exception;
    }

    interface Done {
        void ok(Object result) throws Exception;
    }

    /** Dosya işini arka planda çalıştırır, sonucu arayüz iş parçacığında işler. */
    private void runIo(String busyMsg, final Job job, final Done done) {
        setStatus(busyMsg);
        io.execute(new Runnable() {
            @Override
            public void run() {
                Object result = null;
                Exception error = null;
                try {
                    result = job.run();
                } catch (Exception ex) {
                    error = ex;
                } catch (OutOfMemoryError oom) {
                    error = new Exception("Dosya bu cihaz için çok büyük (bellek yetersiz)");
                }
                final Object r = result;
                final Exception e = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (e != null) {
                            setStatus("Hata: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                            return;
                        }
                        try {
                            if (done != null) done.ok(r);
                        } catch (Exception ex) {
                            setStatus("Hata: " + ex.getMessage());
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        workDir = new File(base, "EFOS-Ofis");
        workDir.mkdirs();

        initDefaultData();
        buildRootUi();
        // Android 15 (API 35) uygulamayı kenardan kenara çizer: içerik durum ve gezinme
        // çubuklarının altında kalmasın diye sistem boşlukları kadar iç kenar bırakılır.
        rootLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        setContentView(rootLayout);
        selectTab(0);
    }

    private void initDefaultData() {
        sheetRawCells.put("0:0", "Kalem");
        sheetRawCells.put("0:1", "Tutar");
        sheetRawCells.put("1:0", "Kira");
        sheetRawCells.put("1:1", "12.000");
        sheetRawCells.put("2:0", "Fatura");
        sheetRawCells.put("2:1", "3.000,50");
        sheetRawCells.put("3:0", "Toplam");
        sheetRawCells.put("3:1", "=TOPLA(B2:B3)");

        slides.add(new OfficeEngines.SlideItem(
                "EFOS-Ofis Mobil Sunu",
                "• 16:9 PowerPoint (.pptx) uyumlu\n• Masaüstü EFOS-Ofis ile uyumlu",
                ""
        ));
    }

    private File fileFrom(EditText nameInput, String fallback) {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = fallback;
        // Yol ayırıcıları ile çalışma klasörü dışına çıkılmasın
        name = name.replace('/', '_').replace('\\', '_');
        return new File(workDir, name);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Kaydedilen dosya telefondan açıldıysa asıl konumuna da yazar. Mesaj eki döndürür. */
    private String writeBackIfExternal(File f) {
        Uri uri = externalSources.get(f.getAbsolutePath());
        if (uri == null) return "";
        try {
            copyFileToUri(f, uri);
            return " • telefondaki asıl dosya da güncellendi";
        } catch (Exception ex) {
            return " • asıl dosyaya yazılamadı (" + ex.getMessage() + "), \"Telefona Kaydet\" ile aktarın";
        }
    }

    private void copyFileToUri(File f, Uri uri) throws Exception {
        try (InputStream in = new FileInputStream(f);
             OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("yazma izni yok");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }

    private GradientDrawable cardBg(int bgColor, int strokeColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private Button makeBtn(String text, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        if (primary) {
            b.setBackground(cardBg(Color.parseColor("#1D4ED8"), 0, 8));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackground(cardBg(Color.parseColor("#E2E8F0"), Color.parseColor("#CBD5E1"), 8));
            b.setTextColor(Color.parseColor("#0F172A"));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private EditText makeField(String initial, String hint, boolean multiline) {
        EditText et = new EditText(this);
        et.setText(initial);
        et.setHint(hint);
        et.setTextSize(14f);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setBackground(cardBg(Color.WHITE, Color.parseColor("#CBD5E1"), 8));
        et.setTextColor(Color.parseColor("#0F172A"));
        et.setHintTextColor(Color.parseColor("#64748B"));
        if (multiline) {
            et.setSingleLine(false);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setSingleLine(true);
        }
        return et;
    }

    private LinearLayout.LayoutParams weightLp(float weight, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, weight);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        return lp;
    }

    private void setStatus(String msg) {
        if (statusBar != null) statusBar.setText(msg);
    }

    private void buildRootUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#F1F5F9"));

        // Üst başlık çubuğu
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#1D4ED8"));
        topBar.setPadding(dp(14), dp(12), dp(10), dp(12));
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = new TextView(this);
        titleTv.setText("EFOS-Ofis v" + APP_VERSION);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18f);
        titleTv.setTypeface(null, Typeface.BOLD);

        TextView subTv = new TextView(this);
        subTv.setText("Belge • Tablo • Sunu • PDF • ZIP");
        subTv.setTextColor(Color.parseColor("#DBEAFE"));
        subTv.setTextSize(11f);
        titleBox.addView(titleTv);
        titleBox.addView(subTv);
        topBar.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button openExtBtn = new Button(this);
        openExtBtn.setText("Dosya Aç");
        openExtBtn.setAllCaps(false);
        openExtBtn.setTextColor(Color.WHITE);
        openExtBtn.setTextSize(12f);
        openExtBtn.setBackground(cardBg(Color.parseColor("#2563EB"), Color.parseColor("#60A5FA"), 8));
        openExtBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        openExtBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemFilePicker();
            }
        });
        topBar.addView(openExtBtn);

        final Button themeBtn = new Button(this);
        themeBtn.setText("Koyu");
        themeBtn.setAllCaps(false);
        themeBtn.setTextColor(Color.WHITE);
        themeBtn.setTextSize(12f);
        themeBtn.setBackground(cardBg(Color.parseColor("#1E40AF"), Color.parseColor("#60A5FA"), 8));
        themeBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(6);
        themeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                darkMode = !darkMode;
                themeBtn.setText(darkMode ? "Açık" : "Koyu");
                applyThemeColors();
            }
        });
        topBar.addView(themeBtn, tLp);
        rootLayout.addView(topBar);

        // İçerik alanı
        contentContainer = new FrameLayout(this);
        contentContainer.setPadding(dp(10), dp(10), dp(10), dp(6));
        tabViews[0] = buildDocTab();
        tabViews[1] = buildSheetTab();
        tabViews[2] = buildSlideTab();
        tabViews[3] = buildPdfTab();
        tabViews[4] = buildZipTab();
        for (View v : tabViews) {
            v.setVisibility(View.GONE);
            contentContainer.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        rootLayout.addView(contentContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Durum çubuğu
        statusBar = new TextView(this);
        statusBar.setText("EFOS-Ofis hazır • Belgeler: " + workDir.getName());
        statusBar.setTextSize(11f);
        statusBar.setSingleLine(true);
        statusBar.setEllipsize(TextUtils.TruncateAt.END);
        statusBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBar.setBackgroundColor(Color.parseColor("#E2E8F0"));
        statusBar.setTextColor(Color.parseColor("#1E293B"));
        rootLayout.addView(statusBar);

        // Alt 5 sekme gezinme çubuğu
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.WHITE);
        navBar.setPadding(dp(4), dp(6), dp(4), dp(8));
        String[] labels = new String[]{"Belge", "Tablo", "Sunu", "PDF", "ZIP"};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button nb = new Button(this);
            nb.setText(labels[i]);
            nb.setAllCaps(false);
            nb.setTextSize(12f);
            nb.setPadding(dp(2), dp(6), dp(2), dp(6));
            nb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            navButtons.add(nb);
            navBar.addView(nb, weightLp(1f, dp(42)));
        }
        rootLayout.addView(navBar);
    }

    private void applyThemeColors() {
        rootLayout.setBackgroundColor(darkMode ? Color.parseColor("#0F172A") : Color.parseColor("#F1F5F9"));
        topBar.setBackgroundColor(darkMode ? Color.parseColor("#1E293B") : Color.parseColor("#1D4ED8"));
        statusBar.setBackgroundColor(darkMode ? Color.parseColor("#1E293B") : Color.parseColor("#E2E8F0"));
        statusBar.setTextColor(darkMode ? Color.parseColor("#E2E8F0") : Color.parseColor("#1E293B"));
        navBar.setBackgroundColor(darkMode ? Color.parseColor("#1E293B") : Color.WHITE);
        selectTab(activeTab);
    }

    private void selectTab(int index) {
        activeTab = index;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            Button nb = navButtons.get(i);
            if (i == index) {
                nb.setBackground(cardBg(Color.parseColor("#1D4ED8"), 0, 8));
                nb.setTextColor(Color.WHITE);
                nb.setTypeface(null, Typeface.BOLD);
            } else {
                nb.setBackground(cardBg(darkMode ? Color.parseColor("#334155") : Color.parseColor("#F1F5F9"), 0, 8));
                nb.setTextColor(darkMode ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155"));
                nb.setTypeface(null, Typeface.NORMAL);
            }
        }
    }

    // ==================== Dışa aktarma ve paylaşma ====================

    private LinearLayout exportRow(final EditText nameInput, final String fallback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(6));
        row.addView(makeBtn("Telefona Kaydet…", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportToDevice(fileFrom(nameInput, fallback));
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(makeBtn("Paylaş", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareFile(fileFrom(nameInput, fallback));
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Sistem "Farklı Kaydet" ekranıyla dosyayı İndirilenler, Drive vb. konuma kopyalar. */
    private void exportToDevice(File f) {
        if (!f.isFile()) {
            setStatus("Önce kaydedin: " + f.getName() + " bulunamadı");
            return;
        }
        pendingExport = f;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(EfosFileProvider.mimeFor(f.getName()));
        intent.putExtra(Intent.EXTRA_TITLE, f.getName());
        startActivityForResult(intent, REQ_EXPORT_DOCUMENT);
    }

    private void shareFile(File f) {
        if (!f.isFile()) {
            setStatus("Önce kaydedin: " + f.getName() + " bulunamadı");
            return;
        }
        Uri uri = EfosFileProvider.uriFor(f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(EfosFileProvider.mimeFor(f.getName()));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        send.setClipData(ClipData.newRawUri(f.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, f.getName() + " paylaş"));
    }

    // ==================== 1. BELGE SEKMESİ (.docx / .md / PDF) ====================

    private View buildDocTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        docNameInput = makeField("belge.docx", "Dosya adı (.docx / .md / .txt)", false);
        col.addView(docNameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        actions.addView(makeBtn("Aç", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDocFromWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("Kaydet", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveDocToWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("PDF Yap", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportDocToPdf();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(actions);
        col.addView(exportRow(docNameInput, "belge.docx"));

        docEditor = makeField(
                "EFOS-Ofis Mobil Belge\n\nWord (.docx) belgenizi yazıp kaydedebilir veya tek dokunuşla PDF oluşturabilirsiniz.\n"
                        + "Telefondan açtığınız belgelerin biçimi (yazı tipi, başlık, liste, resim, üstbilgi) kaydederken korunur.",
                "Belge içeriğinizi buraya yazın...",
                true
        );
        col.addView(docEditor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void openDocFromWorkDir() {
        final File f = fileFrom(docNameInput, "belge.docx");
        if (!f.isFile()) {
            setStatus("Dosya bulunamadı: " + f.getName());
            return;
        }
        runIo("Açılıyor: " + f.getName() + "…", new Job() {
            @Override
            public Object run() throws Exception {
                return lower(f.getName()).endsWith(".docx") ? OfficeEngines.readDocx(f) : OfficeEngines.readUtf8(f);
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                docEditor.setText((String) result);
                docSource = lower(f.getName()).endsWith(".docx") ? f : null;
                setStatus("Belge açıldı: " + f.getName());
            }
        });
    }

    private void saveDocToWorkDir() {
        String name = docNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "belge.docx";
        if (!name.contains(".")) name += ".docx";
        docNameInput.setText(name);
        final File f = fileFrom(docNameInput, "belge.docx");
        final String txt = docEditor.getText().toString();
        final File template = docSource != null && docSource.isFile() ? docSource : null;
        runIo("Kaydediliyor…", new Job() {
            @Override
            public Object run() throws Exception {
                if (lower(f.getName()).endsWith(".docx")) {
                    OfficeEngines.saveDocx(f, txt, template);
                } else {
                    OfficeEngines.writeUtf8(f, txt);
                }
                return writeBackIfExternal(f);
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                if (lower(f.getName()).endsWith(".docx")) docSource = f;
                setStatus("Kaydedildi: " + f.getName() + result);
            }
        });
    }

    private void exportDocToPdf() {
        String name = docNameInput.getText().toString().trim();
        final String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : (name.isEmpty() ? "belge" : name);
        final String pdfName = base + ".pdf";
        final File pdfFile = new File(workDir, pdfName.replace('/', '_'));
        final String text = docEditor.getText().toString();
        runIo("PDF hazırlanıyor…", new Job() {
            @Override
            public Object run() throws Exception {
                int pages = PdfAndroidEngine.createPdfFromText(pdfFile, base, text);
                return new Object[]{pages, PdfAndroidEngine.renderPdfPage(pdfFile, 0)};
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                Object[] r = (Object[]) result;
                pdfNameInput.setText(pdfFile.getName());
                showRenderedPage((PdfAndroidEngine.RenderedPage) r[1]);
                setStatus("PDF dışa aktarıldı (" + r[0] + " sayfa): " + pdfFile.getName());
            }
        });
    }

    // ==================== 2. TABLO SEKMESİ (.xlsx / .csv + Formül) ====================

    private View buildSheetTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        sheetNameInput = makeField("tablo.xlsx", "Tablo adı (.xlsx / .csv)", false);
        col.addView(sheetNameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        actions.addView(makeBtn("Aç", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSheetFromWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetCalcBtn = makeBtn("Hesapla", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSheetEvaluate();
            }
        });
        actions.addView(sheetCalcBtn, weightLp(1.2f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("Kaydet", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSheetToWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(actions);
        col.addView(exportRow(sheetNameInput, "tablo.xlsx"));

        sheetFormulaBanner = new TextView(this);
        sheetFormulaBanner.setText("İpucu: =TOPLA(B2:B3), =YUVARLA(B2*1,18;2), =DÜŞEYARA(A2;D:E;2;0). "
                + "Başında sıfır olan numaralar (00123) metin olarak korunur.");
        sheetFormulaBanner.setTextSize(12f);
        sheetFormulaBanner.setPadding(dp(10), dp(8), dp(10), dp(8));
        sheetFormulaBanner.setBackground(cardBg(Color.parseColor("#DBEAFE"), Color.parseColor("#93C5FD"), 8));
        sheetFormulaBanner.setTextColor(Color.parseColor("#1E3A8A"));
        LinearLayout.LayoutParams banLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        banLp.bottomMargin = dp(6);
        col.addView(sheetFormulaBanner, banLp);

        ScrollView vScroll = new ScrollView(this);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        sheetGrid = new LinearLayout(this);
        sheetGrid.setOrientation(LinearLayout.VERTICAL);
        hScroll.addView(sheetGrid);
        vScroll.addView(hScroll);
        col.addView(vScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rebuildSheetGrid();
        return col;
    }

    /** Izgarayı yüklenen verinin boyutuna göre (sınırlar içinde) yeniden kurar. */
    private void rebuildSheetGrid() {
        int maxR = -1;
        int maxC = -1;
        for (String key : sheetRawCells.keySet()) {
            String[] p = key.split(":");
            maxR = Math.max(maxR, Integer.parseInt(p[0]));
            maxC = Math.max(maxC, Integer.parseInt(p[1]));
        }
        sheetRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, maxR + 4));
        sheetCols = Math.max(MIN_COLS, Math.min(MAX_COLS, maxC + 2));
        sheetGrid.removeAllViews();
        sheetInputs = new EditText[sheetRows][sheetCols];

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.addView(makeHeaderCell("#", dp(38)));
        for (int c = 0; c < sheetCols; c++) {
            hdr.addView(makeHeaderCell(FormulaEngine.indexToColLetters(c), dp(100)));
        }
        sheetGrid.addView(hdr);
        for (int r = 0; r < sheetRows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(makeHeaderCell(String.valueOf(r + 1), dp(38)));
            for (int c = 0; c < sheetCols; c++) {
                EditText cell = new EditText(this);
                cell.setSingleLine(true);
                cell.setTextSize(13f);
                cell.setPadding(dp(6), dp(4), dp(6), dp(4));
                cell.setBackground(cardBg(Color.WHITE, Color.parseColor("#CBD5E1"), 0));
                cell.setTextColor(Color.parseColor("#0F172A"));
                if (r == 0) cell.setTypeface(null, Typeface.BOLD);
                sheetInputs[r][c] = cell;
                row.addView(cell, new LinearLayout.LayoutParams(dp(100), dp(38)));
            }
            sheetGrid.addView(row);
        }
        refreshSheetGridUi();
    }

    private TextView makeHeaderCell(String label, int widthPx) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#334155"));
        tv.setBackground(cardBg(Color.parseColor("#E2E8F0"), Color.parseColor("#CBD5E1"), 0));
        tv.setLayoutParams(new LinearLayout.LayoutParams(widthPx, dp(34)));
        return tv;
    }

    /** Ekrandaki hücreleri belleğe alır. Izgaranın dışında kalan hücrelere dokunmaz. */
    private void pullSheetInputsToStore() {
        if (sheetShowEvaluated) return;
        for (int r = 0; r < sheetRows; r++) {
            for (int c = 0; c < sheetCols; c++) {
                String key = FormulaEngine.cellKey(r, c);
                String txt = sheetInputs[r][c].getText().toString();
                if (txt.trim().isEmpty()) sheetRawCells.remove(key);
                else sheetRawCells.put(key, txt.trim());
            }
        }
    }

    private void refreshSheetGridUi() {
        final Map<String, String> snapshot = sheetRawCells;
        FormulaEngine engine = new FormulaEngine(new FormulaEngine.CellProvider() {
            @Override
            public String getRawCell(int row, int col) {
                String v = snapshot.get(FormulaEngine.cellKey(row, col));
                return v != null ? v : "";
            }
        }, new FormulaEngine.BoundsProvider() {
            @Override
            public int rowCount() {
                int max = 0;
                for (String k : snapshot.keySet()) max = Math.max(max, Integer.parseInt(k.split(":")[0]) + 1);
                return Math.max(max, sheetRows);
            }
        });
        int formulas = 0;
        int errors = 0;
        for (int r = 0; r < sheetRows; r++) {
            for (int c = 0; c < sheetCols; c++) {
                EditText et = sheetInputs[r][c];
                String raw = sheetRawCells.get(FormulaEngine.cellKey(r, c));
                if (raw == null) raw = "";
                if (sheetShowEvaluated && raw.startsWith("=")) {
                    String val = FormulaEngine.formatDisplayValue(engine.evaluateCell(r, c));
                    et.setText(val);
                    boolean err = FormulaEngine.ERROR_CODES.contains(val);
                    et.setTextColor(Color.parseColor(err ? "#B91C1C" : "#1D4ED8"));
                    formulas++;
                    if (err) errors++;
                } else {
                    et.setText(sheetShowEvaluated && raw.startsWith("'") ? raw.substring(1) : raw);
                    et.setTextColor(Color.parseColor("#0F172A"));
                }
                et.setEnabled(!sheetShowEvaluated);
            }
        }
        if (sheetShowEvaluated) {
            sheetFormulaBanner.setText(formulas == 0 ? "Formül hücresi bulunamadı."
                    : formulas + " formül hesaplandı" + (errors > 0 ? " • " + errors + " hücrede hata var (kırmızı)" : "."));
            sheetCalcBtn.setText("Formüller");
        } else {
            sheetCalcBtn.setText("Hesapla");
        }
    }

    private void toggleSheetEvaluate() {
        if (!sheetShowEvaluated) {
            pullSheetInputsToStore();
            sheetShowEvaluated = true;
            refreshSheetGridUi();
            setStatus("Formüller hesaplandı.");
        } else {
            sheetShowEvaluated = false;
            refreshSheetGridUi();
            setStatus("Formül düzenleme moduna geçildi.");
        }
    }

    private void openSheetFromWorkDir() {
        final File f = fileFrom(sheetNameInput, "tablo.xlsx");
        if (!f.isFile()) {
            setStatus("Tablo bulunamadı: " + f.getName());
            return;
        }
        final boolean isCsv = lower(f.getName()).endsWith(".csv");
        runIo("Tablo açılıyor…", new Job() {
            @Override
            public Object run() throws Exception {
                Map<String, String> loaded = isCsv ? OfficeEngines.readCsv(f) : OfficeEngines.readXlsx(f);
                int sheets = isCsv ? 1 : OfficeEngines.xlsxSheetCount(f);
                return new Object[]{loaded, sheets};
            }
        }, new Done() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object result) {
                Object[] r = (Object[]) result;
                Map<String, String> loaded = (Map<String, String>) r[0];
                int sheets = (Integer) r[1];
                sheetRawCells.clear();
                sheetRawCells.putAll(loaded);
                sheetShowEvaluated = false;
                sheetSource = isCsv ? null : f;
                rebuildSheetGrid();
                StringBuilder note = new StringBuilder("Tablo açıldı: " + f.getName());
                if (sheets > 1) note.append(" • yalnızca ilk sayfa düzenlenir, diğer ").append(sheets - 1).append(" sayfa korunur");
                int maxR = 0;
                int maxC = 0;
                for (String k : loaded.keySet()) {
                    String[] p = k.split(":");
                    maxR = Math.max(maxR, Integer.parseInt(p[0]) + 1);
                    maxC = Math.max(maxC, Integer.parseInt(p[1]) + 1);
                }
                if (maxR > sheetRows || maxC > sheetCols) {
                    note.append(" • ilk ").append(sheetRows).append("×").append(sheetCols)
                            .append(" hücre gösteriliyor, diğerleri kaydederken korunur");
                }
                setStatus(note.toString());
            }
        });
    }

    private void saveSheetToWorkDir() {
        pullSheetInputsToStore();
        String name = sheetNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "tablo.xlsx";
        String low = lower(name);
        if (!low.endsWith(".xlsx") && !low.endsWith(".csv")) name += ".xlsx";
        sheetNameInput.setText(name);
        final File f = fileFrom(sheetNameInput, "tablo.xlsx");
        final Map<String, String> cells = new HashMap<String, String>(sheetRawCells);
        final File template = sheetSource != null && sheetSource.isFile() ? sheetSource : null;
        runIo("Tablo kaydediliyor…", new Job() {
            @Override
            public Object run() throws Exception {
                if (lower(f.getName()).endsWith(".csv")) {
                    OfficeEngines.writeCsv(f, cells, ';');
                } else {
                    OfficeEngines.saveXlsx(f, cells, template);
                }
                return writeBackIfExternal(f);
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                if (lower(f.getName()).endsWith(".xlsx")) sheetSource = f;
                setStatus("Tablo kaydedildi: " + f.getName() + result);
            }
        });
    }

    // ==================== 3. SUNU SEKMESİ (.pptx) ====================

    private View buildSlideTab() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        slideNameInput = makeField("sunu.pptx", "Sunu adı (.pptx)", false);
        col.addView(slideNameInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        actions.addView(makeBtn("Aç", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPptxFromWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("+ Slayt", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                storeCurrentSlide();
                slides.add(new OfficeEngines.SlideItem("Slayt " + (slides.size() + 1), "• Madde 1", ""));
                slideIndex = slides.size() - 1;
                loadCurrentSlide();
                setStatus("Yeni slayt eklendi (" + slides.size() + ")");
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("Kaydet", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePptxToWorkDir();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(actions);
        col.addView(exportRow(slideNameInput, "sunu.pptx"));

        // 16:9 Canlı Slayt Kartı
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 10));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setMinimumHeight(dp(185));

        View accent = new View(this);
        accent.setBackgroundColor(Color.parseColor("#38BDF8"));
        card.addView(accent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        slidePreviewTitle = new TextView(this);
        slidePreviewTitle.setTextColor(Color.parseColor("#38BDF8"));
        slidePreviewTitle.setTextSize(17f);
        slidePreviewTitle.setTypeface(null, Typeface.BOLD);
        slidePreviewTitle.setPadding(0, dp(10), 0, dp(6));
        card.addView(slidePreviewTitle);

        slidePreviewBody = new TextView(this);
        slidePreviewBody.setTextColor(Color.parseColor("#F8FAFC"));
        slidePreviewBody.setTextSize(13f);
        card.addView(slidePreviewBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        slidePreviewNum = new TextView(this);
        slidePreviewNum.setTextColor(Color.parseColor("#94A3B8"));
        slidePreviewNum.setTextSize(11f);
        slidePreviewNum.setGravity(Gravity.END);
        card.addView(slidePreviewNum);
        col.addView(card);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(8), 0, dp(8));
        nav.addView(makeBtn("◀ Önceki", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveSlide(-1);
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        slideCounterLbl = new TextView(this);
        slideCounterLbl.setGravity(Gravity.CENTER);
        slideCounterLbl.setTypeface(null, Typeface.BOLD);
        nav.addView(slideCounterLbl, weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        nav.addView(makeBtn("Sonraki ▶", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveSlide(1);
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(nav);

        slideTitleInput = makeField("", "Slayt başlığı", false);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = dp(6);
        col.addView(slideTitleInput, stLp);

        slideBodyInput = makeField("", "Slayt içeriği...", true);
        slideBodyInput.setMinLines(4);
        col.addView(slideBodyInput);

        Button previewRefresh = makeBtn("Önizlemeyi Güncelle", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                storeCurrentSlide();
                loadCurrentSlide();
            }
        });
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(6);
        col.addView(previewRefresh, prLp);

        loadCurrentSlide();
        scroll.addView(col);
        return scroll;
    }

    private void storeCurrentSlide() {
        if (slideIndex >= 0 && slideIndex < slides.size()) {
            slides.get(slideIndex).title = slideTitleInput.getText().toString();
            slides.get(slideIndex).body = slideBodyInput.getText().toString();
        }
    }

    private void loadCurrentSlide() {
        if (slides.isEmpty()) slides.add(new OfficeEngines.SlideItem("Slayt 1", "", ""));
        slideIndex = Math.max(0, Math.min(slideIndex, slides.size() - 1));
        OfficeEngines.SlideItem s = slides.get(slideIndex);
        slideTitleInput.setText(s.title);
        slideBodyInput.setText(s.body);
        slidePreviewTitle.setText(s.title.isEmpty() ? "(Başlıksız Slayt)" : s.title);
        slidePreviewBody.setText(s.body);
        String counter = "Slayt " + (slideIndex + 1) + " / " + slides.size();
        slideCounterLbl.setText(counter);
        slidePreviewNum.setText((slideIndex + 1) + " / " + slides.size());
    }

    private void moveSlide(int delta) {
        storeCurrentSlide();
        int next = slideIndex + delta;
        if (next >= 0 && next < slides.size()) {
            slideIndex = next;
            loadCurrentSlide();
        }
    }

    // ==================== 4. PDF SEKMESİ ====================

    private View buildPdfTab() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        pdfNameInput = makeField("rapor.pdf", "PDF dosya adı (.pdf)", false);
        col.addView(pdfNameInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        actions.addView(makeBtn("PDF Aç", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPdfPage(0);
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(makeBtn("PDF Oluştur", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createQuickPdf();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(actions);
        col.addView(exportRow(pdfNameInput, "rapor.pdf"));

        pdfTextInput = makeField(
                "EFOS-Ofis Mobil üzerinden üretilen Türkçe PDF raporu (ŞİĞÜÖÇ).",
                "Yeni PDF oluşturmak için metin girin...",
                true
        );
        pdfTextInput.setMinLines(2);
        col.addView(pdfTextInput);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(6), 0, dp(6));
        nav.addView(makeBtn("◀ Önceki", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pdfPageIndex > 0) openPdfPage(pdfPageIndex - 1);
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        pdfPageLbl = new TextView(this);
        pdfPageLbl.setText("Sayfa 0 / 0");
        pdfPageLbl.setGravity(Gravity.CENTER);
        pdfPageLbl.setTypeface(null, Typeface.BOLD);
        nav.addView(pdfPageLbl, weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        nav.addView(makeBtn("Sonraki ▶", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pdfPageIndex + 1 < pdfTotalPages) openPdfPage(pdfPageIndex + 1);
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(nav);

        pdfImageView = new ImageView(this);
        pdfImageView.setAdjustViewBounds(true);
        pdfImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfImageView.setBackground(cardBg(Color.WHITE, Color.parseColor("#CBD5E1"), 8));
        pdfImageView.setMinimumHeight(dp(260));
        col.addView(pdfImageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(col);
        return scroll;
    }

    // ==================== 5. ZIP & DOSYALAR SEKMESİ ====================

    private View buildZipTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        zipNameInput = makeField("efos_paket.zip", "ZIP arşiv adı (.zip)", false);
        col.addView(zipNameInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));

        actions.addView(makeBtn("Listele", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listZipArchive();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        actions.addView(makeBtn("Ayıkla", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                extractZipArchive();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        actions.addView(makeBtn("Paketle", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                packWorkDirToZip();
            }
        }), weightLp(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(actions);
        col.addView(exportRow(zipNameInput, "efos_paket.zip"));

        Button listDirBtn = makeBtn("Çalışma Klasöründeki Dosyaları Göster", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWorkDirFiles();
            }
        });
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.bottomMargin = dp(6);
        col.addView(listDirBtn, dLp);

        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(cardBg(Color.WHITE, Color.parseColor("#CBD5E1"), 8));
        zipLogView = new TextView(this);
        zipLogView.setText("ZIP ayıklamak, listelemek veya çalışma belgelerinizi paketlemek için butonları kullanın.");
        zipLogView.setTextSize(13f);
        zipLogView.setTextColor(Color.parseColor("#0F172A"));
        zipLogView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logScroll.addView(zipLogView);
        col.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void openPptxFromWorkDir() {
        final File f = fileFrom(slideNameInput, "sunu.pptx");
        if (!f.isFile()) {
            setStatus("Sunu bulunamadı: " + f.getName());
            return;
        }
        runIo("Sunu açılıyor…", new Job() {
            @Override
            public Object run() throws Exception {
                return OfficeEngines.readPptx(f);
            }
        }, new Done() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object result) {
                slides.clear();
                slides.addAll((List<OfficeEngines.SlideItem>) result);
                slideIndex = 0;
                slideSource = f;
                loadCurrentSlide();
                setStatus("Sunu açıldı (" + slides.size() + " slayt): " + f.getName());
            }
        });
    }

    private void savePptxToWorkDir() {
        storeCurrentSlide();
        loadCurrentSlide();
        String name = slideNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "sunu.pptx";
        if (!lower(name).endsWith(".pptx")) name += ".pptx";
        slideNameInput.setText(name);
        final File f = fileFrom(slideNameInput, "sunu.pptx");
        final List<OfficeEngines.SlideItem> copy = new ArrayList<OfficeEngines.SlideItem>();
        for (OfficeEngines.SlideItem s : slides) copy.add(new OfficeEngines.SlideItem(s.title, s.body, s.notes));
        final File template = slideSource != null && slideSource.isFile() ? slideSource : null;
        runIo("Sunu kaydediliyor…", new Job() {
            @Override
            public Object run() throws Exception {
                OfficeEngines.savePptx(f, copy, template);
                return writeBackIfExternal(f);
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                slideSource = f;
                setStatus("Sunu kaydedildi (" + copy.size() + " slayt): " + f.getName() + result);
            }
        });
    }

    private void showRenderedPage(PdfAndroidEngine.RenderedPage res) {
        pdfPageIndex = res.pageIndex;
        pdfTotalPages = res.totalPages;
        pdfImageView.setImageBitmap(res.bitmap);
        pdfPageLbl.setText("Sayfa " + (pdfPageIndex + 1) + " / " + pdfTotalPages);
    }

    private void openPdfPage(final int pageIdx) {
        final File f = fileFrom(pdfNameInput, "rapor.pdf");
        if (!f.isFile()) {
            setStatus("PDF bulunamadı: " + f.getName());
            return;
        }
        runIo("PDF yükleniyor…", new Job() {
            @Override
            public Object run() throws Exception {
                return PdfAndroidEngine.renderPdfPage(f, pageIdx);
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                showRenderedPage((PdfAndroidEngine.RenderedPage) result);
                setStatus("PDF yüklendi: " + f.getName() + " (Sayfa " + (pdfPageIndex + 1) + "/" + pdfTotalPages + ")");
            }
        });
    }

    private void createQuickPdf() {
        String name = pdfNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "rapor.pdf";
        if (!lower(name).endsWith(".pdf")) name += ".pdf";
        pdfNameInput.setText(name);
        final File f = fileFrom(pdfNameInput, "rapor.pdf");
        final String title = f.getName().substring(0, f.getName().length() - 4);
        final String text = pdfTextInput.getText().toString();
        runIo("PDF oluşturuluyor…", new Job() {
            @Override
            public Object run() throws Exception {
                int pages = PdfAndroidEngine.createPdfFromText(f, title, text);
                return new Object[]{pages, PdfAndroidEngine.renderPdfPage(f, 0)};
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                Object[] r = (Object[]) result;
                showRenderedPage((PdfAndroidEngine.RenderedPage) r[1]);
                setStatus("PDF oluşturuldu (" + r[0] + " sayfa): " + f.getName());
            }
        });
    }

    private static String describeEntries(List<OfficeEngines.ZipEntryInfo> entries) {
        StringBuilder sb = new StringBuilder();
        for (OfficeEngines.ZipEntryInfo e : entries) {
            sb.append(e.isDir ? "[KLASÖR] " : "").append(e.name).append(" (").append(e.size).append(" B)\n");
        }
        return sb.length() > 0 ? sb.toString() : "(Arşiv boş)";
    }

    private void listZipArchive() {
        final File f = fileFrom(zipNameInput, "efos_paket.zip");
        if (!f.isFile()) {
            setStatus("ZIP bulunamadı: " + f.getName());
            return;
        }
        runIo("ZIP okunuyor…", new Job() {
            @Override
            public Object run() throws Exception {
                return OfficeEngines.listZip(f);
            }
        }, new Done() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object result) {
                List<OfficeEngines.ZipEntryInfo> entries = (List<OfficeEngines.ZipEntryInfo>) result;
                zipLogView.setText(describeEntries(entries));
                setStatus("ZIP listelendi: " + entries.size() + " öğe");
            }
        });
    }

    private void extractZipArchive() {
        final File f = fileFrom(zipNameInput, "efos_paket.zip");
        if (!f.isFile()) {
            setStatus("ZIP bulunamadı: " + f.getName());
            return;
        }
        String name = f.getName();
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        final File outDir = new File(workDir, stem + "_ayiklanan");
        runIo("ZIP ayıklanıyor…", new Job() {
            @Override
            public Object run() throws Exception {
                return OfficeEngines.extractZip(f, outDir);
            }
        }, new Done() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object result) {
                List<File> files = (List<File>) result;
                StringBuilder sb = new StringBuilder();
                for (File ef : files) sb.append("• ").append(outDir.getName()).append("/").append(ef.getName()).append("\n");
                zipLogView.setText(sb.length() > 0 ? sb.toString() : "(Ayıklanacak dosya bulunamadı)");
                setStatus(files.size() + " dosya ayıklandı → " + outDir.getName());
            }
        });
    }

    private void packWorkDirToZip() {
        String name = zipNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "efos_paket.zip";
        if (!lower(name).endsWith(".zip")) name += ".zip";
        zipNameInput.setText(name);
        final File outZip = fileFrom(zipNameInput, "efos_paket.zip");
        runIo("ZIP oluşturuluyor…", new Job() {
            @Override
            public Object run() throws Exception {
                int count = OfficeEngines.createZip(Collections.singletonList(workDir), outZip, true);
                return new Object[]{count, OfficeEngines.listZip(outZip)};
            }
        }, new Done() {
            @Override
            @SuppressWarnings("unchecked")
            public void ok(Object result) {
                Object[] r = (Object[]) result;
                zipLogView.setText(describeEntries((List<OfficeEngines.ZipEntryInfo>) r[1]));
                setStatus("ZIP oluşturuldu (" + r[0] + " dosya): " + outZip.getName());
            }
        });
    }

    private void showWorkDirFiles() {
        File[] files = workDir.listFiles();
        if (files == null || files.length == 0) {
            zipLogView.setText("Çalışma klasörü henüz boş. Belge, Tablo, Sunu veya PDF kaydedin.");
            setStatus("Klasörde dosya yok.");
            return;
        }
        Arrays.sort(files);
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (f.getName().endsWith(".efos-tmp") || f.getName().endsWith(".efos-new")) continue;
            sb.append(f.isDirectory() ? "[KLASÖR] " : "• ")
                    .append(f.getName())
                    .append(f.isDirectory() ? "" : " (" + f.length() + " B)")
                    .append(externalSources.containsKey(f.getAbsolutePath()) ? "  ↔ telefondaki dosyaya bağlı" : "")
                    .append("\n");
        }
        sb.append("\nNot: Bu klasör uygulamaya özeldir ve uygulama kaldırılınca silinir. "
                + "Kalıcı saklamak için \"Telefona Kaydet…\" veya \"Paylaş\" kullanın.");
        zipLogView.setText(sb.toString());
        setStatus("Klasör tarandı: " + files.length + " öğe");
    }

    // ==================== SİSTEM DOSYA SEÇİCİ (SAF) ====================

    private void launchSystemFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQ_EXPORT_DOCUMENT) {
            final File src = pendingExport;
            pendingExport = null;
            if (src == null) return;
            runIo("Telefona kaydediliyor…", new Job() {
                @Override
                public Object run() throws Exception {
                    copyFileToUri(src, uri);
                    return null;
                }
            }, new Done() {
                @Override
                public void ok(Object result) {
                    setStatus("Telefona kaydedildi: " + resolveDisplayName(uri));
                }
            });
            return;
        }
        if (requestCode != REQ_OPEN_DOCUMENT) return;

        boolean writable = false;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            writable = true;
        } catch (SecurityException ex) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Kalıcı izin verilmedi; yine de bu oturumda okunabilir
            }
        }
        final boolean canWriteBack = writable;
        final String fileName = resolveDisplayName(uri).replace('/', '_').replace('\\', '_');
        final File dest = new File(workDir, fileName);
        runIo("İçe aktarılıyor: " + fileName + "…", new Job() {
            @Override
            public Object run() throws Exception {
                byte[] buf = new byte[16384];
                File tmp = new File(dest.getAbsolutePath() + ".efos-tmp");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IllegalStateException("Dosya okunamadı");
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(dest)) {
                    dest.delete();
                    if (!tmp.renameTo(dest)) throw new IllegalStateException("Dosya kopyalanamadı");
                }
                return null;
            }
        }, new Done() {
            @Override
            public void ok(Object result) {
                if (canWriteBack) externalSources.put(dest.getAbsolutePath(), uri);
                else externalSources.remove(dest.getAbsolutePath());
                String low = lower(fileName);
                if (low.endsWith(".docx") || low.endsWith(".md") || low.endsWith(".txt")) {
                    docNameInput.setText(fileName);
                    selectTab(0);
                    openDocFromWorkDir();
                } else if (low.endsWith(".xlsx") || low.endsWith(".csv")) {
                    sheetNameInput.setText(fileName);
                    selectTab(1);
                    openSheetFromWorkDir();
                } else if (low.endsWith(".pptx")) {
                    slideNameInput.setText(fileName);
                    selectTab(2);
                    openPptxFromWorkDir();
                } else if (low.endsWith(".pdf")) {
                    pdfNameInput.setText(fileName);
                    selectTab(3);
                    openPdfPage(0);
                } else if (low.endsWith(".zip")) {
                    zipNameInput.setText(fileName);
                    selectTab(4);
                    listZipArchive();
                } else {
                    setStatus("İçe aktarıldı: " + fileName);
                }
            }
        });
    }

    private String resolveDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // ad okunamazsa aşağıdaki varsayılan kullanılır
        }
        return "dosya_" + System.currentTimeMillis();
    }
}
