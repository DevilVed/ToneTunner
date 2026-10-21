package com.devilved.tonetunner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.devilved.tonetunner.metrics.DashboardStats;
import com.devilved.tonetunner.metrics.PriceTable;
import com.devilved.tonetunner.metrics.SessionMetric;
import com.devilved.tonetunner.metrics.UsageStore;
import com.devilved.tonetunner.utils.BarChartView;
import com.devilved.tonetunner.utils.ThemeUtils;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Usage dashboard: words, time saved, API cost, tokens, cache hits, speed, peak hours and microphone. */
public class DashboardActivity extends AppCompatActivity {
    static final String PRICE_PREFERENCES = "dashboard_prices";
    private static final String PERIOD_KEY = "period";

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private List<SessionMetric> metrics = Collections.emptyList();
    private PriceTable prices = new PriceTable(null);
    private DashboardStats.Period period = DashboardStats.Period.WEEK;
    private LinearLayout content;
    private LinearLayout cards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.settings_dashboard_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState != null) {
            period = DashboardStats.Period.values()[savedInstanceState.getInt(PERIOD_KEY, period.ordinal())];
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        content.addView(periodChips(), matchWrap());
        TextView privacy = text(getString(R.string.dashboard_privacy), 12, false);
        privacy.setPadding(dp(4), dp(4), dp(4), dp(8));
        content.addView(privacy, matchWrap());
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        content.addView(cards, matchWrap());
        cards.addView(text(getString(R.string.dashboard_loading), 14, false), matchWrap());
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(PERIOD_KEY, period.ordinal());
    }

    @Override
    protected void onDestroy() {
        ++generation;
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Reads the database and price edits off the main thread, then redraws. */
    private void load() {
        int current = ++generation;
        Context app = getApplicationContext();
        try {
            loader.execute(() -> {
                List<SessionMetric> rows = UsageStore.get(app).all();
                PriceTable table = readPrices(app);
                main.post(() -> {
                    if (current != generation || isFinishing() || isDestroyed()) return;
                    metrics = rows;
                    prices = table;
                    render();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The screen is closing.
        }
    }

    static PriceTable readPrices(Context context) {
        Map<String, PriceTable.Price> edits = new HashMap<>();
        SharedPreferences preferences = context.getSharedPreferences(PRICE_PREFERENCES, MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            PriceTable.Price price = PriceTable.Price.decode(String.valueOf(entry.getValue()));
            if (price != null) edits.put(entry.getKey(), price);
        }
        return new PriceTable(edits);
    }

    private View periodChips() {
        ChipGroup group = new ChipGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        int[] names = {R.string.dashboard_period_today, R.string.dashboard_period_week,
                R.string.dashboard_period_month, R.string.dashboard_period_year, R.string.dashboard_period_all};
        for (DashboardStats.Period value : DashboardStats.Period.values()) {
            Chip chip = new Chip(this);
            chip.setText(names[value.ordinal()]);
            chip.setCheckable(true);
            chip.setChecked(value == period);
            chip.setTag("period_" + value.name());
            chip.setOnClickListener(v -> {
                if (period == value) return;
                period = value;
                render();
            });
            group.addView(chip);
        }
        return group;
    }

    void render() {
        cards.removeAllViews();
        DashboardStats s = DashboardStats.compute(metrics, period, System.currentTimeMillis(),
                TimeZone.getDefault(), prices);
        cards.addView(summary(s), matchWrap());
        cards.addView(wordsCard(s), cardParams());
        cards.addView(costCard(s), cardParams());
        cards.addView(tokensCard(s), cardParams());
        cards.addView(speedCard(s), cardParams());
        cards.addView(peakCard(s), cardParams());
        cards.addView(modelsCard(s), cardParams());
        cards.addView(microphoneCard(s), cardParams());
        cards.addView(reliabilityCard(s), cardParams());
        Button clear = new Button(this);
        clear.setText(R.string.dashboard_clear);
        clear.setAllCaps(false);
        clear.setTag("clear");
        clear.setOnClickListener(v -> confirmClear());
        cards.addView(clear, matchWrap());
    }

    // ---- cards ----

    private View summary(DashboardStats s) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] tiles = {
                {"dictations", getString(R.string.dashboard_tile_dictations), number(s.completed)},
                {"words", getString(R.string.dashboard_tile_words), number(s.words)},
                {"audio", getString(R.string.dashboard_tile_audio), duration(s.audioMs)},
                {"saved", getString(R.string.dashboard_tile_saved), duration(s.timeSavedMs)},
                {"cost", getString(R.string.dashboard_tile_cost), money(s.costUsd)},
                {"tokens", getString(R.string.dashboard_tile_tokens), number(s.totalTokens)},
        };
        for (int row = 0; row < tiles.length; row += 2) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = row; i < row + 2; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                params.setMargins(i == row ? 0 : dp(6), dp(6), i == row ? dp(6) : 0, dp(6));
                line.addView(tile(tiles[i][0], tiles[i][1], tiles[i][2]), params);
            }
            grid.addView(line, matchWrap());
        }
        return grid;
    }

    private View tile(String tag, String label, String value) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        TextView number = text(value, 22, true);
        number.setTag("tile_" + tag);
        body.addView(number, matchWrap());
        body.addView(text(label, 12, false), matchWrap());
        card.setContentDescription(label + ": " + value);
        return card;
    }

    private View wordsCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_words_title), matchWrap());
        body.addView(chart("chart_words", s, new double[][]{column(s, b -> b.words)},
                null, new int[]{seriesColor(1)}, v -> number(Math.round(v))), matchWrap());
        body.addView(text(getString(R.string.dashboard_words_detail, decimal(s.speakingWpm, 0)), 12, false),
                matchWrap());
        return card;
    }

    private View costCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_cost_title), matchWrap());
        body.addView(chart("chart_cost", s, new double[][]{column(s, b -> b.costUsd)},
                null, new int[]{seriesColor(1)}, this::money), matchWrap());
        double perDictation = s.completed + s.failed + s.cancelled == 0 ? Double.NaN
                : s.costUsd / (s.completed + s.failed + s.cancelled);
        double perHour = s.audioMs == 0 ? Double.NaN : s.costUsd / (s.audioMs / 3_600_000.0);
        body.addView(text(getString(R.string.dashboard_cost_detail, money(s.costUsd), money(perDictation),
                money(perHour)), 14, false), matchWrap());
        if (s.unpriced > 0) {
            String note = getResources().getQuantityString(R.plurals.dashboard_cost_unpriced, s.unpriced, s.unpriced);
            body.addView(text(note, 12, false), matchWrap());
        }
        body.addView(text(getString(R.string.dashboard_cost_note), 12, false), matchWrap());
        return card;
    }

    private View tokensCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_tokens_title), matchWrap());
        String[] names = {getString(R.string.dashboard_series_input), getString(R.string.dashboard_series_cached),
                getString(R.string.dashboard_series_output)};
        int[] colors = {seriesColor(1), seriesColor(2), seriesColor(3)};
        body.addView(chart("chart_tokens", s, new double[][]{
                column(s, b -> b.uncachedTokens), column(s, b -> b.cachedTokens), column(s, b -> b.outputTokens)},
                names, colors, v -> number(Math.round(v))), matchWrap());
        long uncached = s.promptTokens - s.cachedTokens + s.transcriptionInputTokens;
        long output = s.completionTokens + s.transcriptionOutputTokens;
        body.addView(legend(names, colors, new String[]{number(uncached), number(s.cachedTokens), number(output)}),
                matchWrap());
        TextView rate = text(Double.isNaN(s.cacheHitRate) ? getString(R.string.dashboard_cache_none)
                : getString(R.string.dashboard_cache_rate, percent(s.cacheHitRate)), 14, false);
        rate.setTag("cache_rate");
        body.addView(rate, matchWrap());
        body.addView(text(getString(R.string.dashboard_tokens_detail, number(s.promptTokens),
                number(s.completionTokens), number(s.transcriptionTokens)), 12, false), matchWrap());
        for (SessionMetric m : metrics) {
            if (m.tokensEstimated) {
                body.addView(text(getString(R.string.dashboard_tokens_estimated), 12, false), matchWrap());
                break;
            }
        }
        return card;
    }

    private View speedCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_speed_title), matchWrap());
        body.addView(row(R.string.dashboard_speed_processing, seconds(s.avgProcessingMs)), matchWrap());
        body.addView(row(R.string.dashboard_speed_factor, Double.isNaN(s.avgSpeedFactor)
                ? getString(R.string.dashboard_none)
                : getString(R.string.dashboard_times_realtime, decimal(s.avgSpeedFactor, 1))), matchWrap());
        body.addView(row(R.string.dashboard_speed_load, seconds(s.avgModelLoadMs)), matchWrap());
        body.addView(row(R.string.dashboard_speed_cloud, seconds(s.avgCloudRequestMs)), matchWrap());
        body.addView(row(R.string.dashboard_speed_enhance, seconds(s.avgEnhancementRequestMs)), matchWrap());
        return card;
    }

    private View peakCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_peak_title), matchWrap());
        String[] labels = new String[24];
        double[] words = new double[24];
        for (int hour = 0; hour < 24; hour++) {
            labels[hour] = hourLabel(hour);
            words[hour] = s.hourWords[hour];
        }
        BarChartView chart = new BarChartView(this);
        chart.setTag("chart_peak");
        chart.setInk(color(com.google.android.material.R.attr.colorOnSurface),
                color(com.google.android.material.R.attr.colorOnSurfaceVariant), gridColor());
        chart.setData(labels, new double[][]{words}, null, new int[]{seriesColor(1)}, v -> number(Math.round(v)),
                getString(R.string.dashboard_no_data));
        if (s.peakStartHour >= 0) chart.setHighlight(s.peakStartHour, 2, seriesColor(3));
        body.addView(chart, matchWrap());
        if (s.peakStartHour >= 0) {
            body.addView(legend(new String[]{getString(R.string.dashboard_peak_series)},
                    new int[]{seriesColor(3)}, new String[]{""}), matchWrap());
            body.addView(text(getString(R.string.dashboard_peak_detail, hourLabel(s.peakStartHour),
                    hourLabel((s.peakStartHour + 2) % 24)), 14, false), matchWrap());
        }
        return card;
    }

    private View modelsCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_models_title), matchWrap());
        body.addView(text(getString(R.string.dashboard_models_hint), 12, false), matchWrap());
        if (s.models.isEmpty()) body.addView(text(getString(R.string.dashboard_no_data), 14, false), matchWrap());
        for (DashboardStats.ModelRow model : s.models) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setMinimumHeight(dp(48));
            String kind = getString("enhancement".equals(model.kind)
                    ? R.string.dashboard_model_enhancement : R.string.dashboard_model_transcription);
            row.addView(text(kind + " · " + model.model, 14, true), matchWrap());
            String cost = model.unpriced ? getString(R.string.dashboard_model_unpriced) : money(model.costUsd);
            String detail = getResources().getQuantityString(R.plurals.dashboard_model_detail, model.sessions,
                    model.sessions, duration(model.audioMs), number(model.tokens), cost, seconds(model.avgRequestMs()));
            row.addView(text(detail, 12, false), matchWrap());
            boolean local = model.provider == null || "LOCAL".equals(model.provider);
            if (!local) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> editPrice(model.provider, model.model));
                android.util.TypedValue ripple = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                row.setBackgroundResource(ripple.resourceId);
            }
            row.setTag("model_" + model.kind + "_" + model.model);
            body.addView(row, matchWrap());
        }
        return card;
    }

    private View microphoneCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_mic_title), matchWrap());
        if (s.micDevices.isEmpty()) {
            body.addView(text(getString(R.string.dashboard_no_data), 14, false), matchWrap());
            return card;
        }
        for (Map.Entry<String, Integer> device : s.micDevices.entrySet()) {
            body.addView(row(deviceName(device.getKey()), number(device.getValue())), matchWrap());
        }
        if (s.micFallbacks > 0) {
            String note = getResources().getQuantityString(R.plurals.dashboard_mic_fallbacks, s.micFallbacks, s.micFallbacks);
            body.addView(text(note, 12, false), matchWrap());
        }
        body.addView(row(R.string.dashboard_mic_level, decibels(s.avgSpeechLevelDb)), matchWrap());
        body.addView(row(R.string.dashboard_mic_peak, decibels(s.maxPeakDb)), matchWrap());
        body.addView(row(R.string.dashboard_mic_gain, Double.isNaN(s.avgGainDb) ? getString(R.string.dashboard_none)
                : "+" + decimal(s.avgGainDb, 1) + " dB"), matchWrap());
        body.addView(row(R.string.dashboard_mic_clipping, Double.isNaN(s.avgClippedRatio)
                ? getString(R.string.dashboard_none) : percent(s.avgClippedRatio)), matchWrap());
        return card;
    }

    private View reliabilityCard(DashboardStats s) {
        MaterialCardView card = card();
        LinearLayout body = body(card);
        body.addView(title(R.string.dashboard_reliability_title), matchWrap());
        TextView detail = text(getString(R.string.dashboard_reliability_detail, s.completed, s.failed, s.cancelled),
                14, false);
        detail.setTag("reliability");
        body.addView(detail, matchWrap());
        return card;
    }

    // ---- actions ----

    private void editPrice(String provider, String model) {
        PriceTable.Price current = prices.price(provider, model);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextInputEditText input = priceField(form, R.string.dashboard_price_input, current == null ? 0 : current.inputPerMillion);
        TextInputEditText cached = priceField(form, R.string.dashboard_price_cached, current == null ? 0 : current.cachedPerMillion);
        TextInputEditText output = priceField(form, R.string.dashboard_price_output, current == null ? 0 : current.outputPerMillion);
        TextInputEditText audio = priceField(form, R.string.dashboard_price_audio, current == null ? 0 : current.audioPerHour);
        SharedPreferences store = getSharedPreferences(PRICE_PREFERENCES, MODE_PRIVATE);
        String key = PriceTable.key(provider, model);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dashboard_price_title, model))
                .setView(form)
                .setPositiveButton(R.string.dashboard_price_save, (dialog, which) -> {
                    PriceTable.Price price = PriceTable.Price.decode(value(input) + "," + value(cached) + ","
                            + value(output) + "," + value(audio));
                    if (price == null) {
                        Toast.makeText(this, R.string.dashboard_price_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    store.edit().putString(key, price.encode()).apply();
                    load();
                })
                .setNegativeButton(R.string.dashboard_cancel, null);
        if (prices.isEdited(provider, model)) {
            builder.setNeutralButton(R.string.dashboard_price_reset, (dialog, which) -> {
                store.edit().remove(key).apply();
                load();
            });
        }
        builder.show();
    }

    private TextInputEditText priceField(LinearLayout form, int hint, double value) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(hint));
        TextInputEditText field = new TextInputEditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(plain(value));
        layout.addView(field, matchWrap());
        form.addView(layout, matchWrap());
        return field;
    }

    private static String value(TextInputEditText field) {
        String text = field.getText() == null ? "" : field.getText().toString().trim().replace(',', '.');
        return text.isEmpty() ? "0" : text;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.dashboard_clear_confirm)
                .setPositiveButton(R.string.dashboard_clear_action, (dialog, which) -> {
                    Context app = getApplicationContext();
                    try {
                        loader.execute(() -> {
                            UsageStore.get(app).clear();
                            main.post(this::load);
                        });
                    } catch (RejectedExecutionException ignored) {
                        // The screen is closing.
                    }
                })
                .setNegativeButton(R.string.dashboard_cancel, null)
                .show();
    }

    // ---- building blocks ----

    private interface BucketValue {
        double of(DashboardStats.Bucket bucket);
    }

    private static double[] column(DashboardStats s, BucketValue value) {
        double[] values = new double[s.buckets.size()];
        for (int i = 0; i < values.length; i++) values[i] = value.of(s.buckets.get(i));
        return values;
    }

    private BarChartView chart(String tag, DashboardStats s, double[][] series, String[] names, int[] colors,
                               BarChartView.Formatter formatter) {
        List<String> labels = new ArrayList<>();
        for (DashboardStats.Bucket bucket : s.buckets) labels.add(bucket.label);
        BarChartView chart = new BarChartView(this);
        chart.setTag(tag);
        chart.setInk(color(com.google.android.material.R.attr.colorOnSurface),
                color(com.google.android.material.R.attr.colorOnSurfaceVariant), gridColor());
        chart.setData(labels.toArray(new String[0]), series, names, colors, formatter,
                getString(R.string.dashboard_no_data));
        return chart;
    }

    private View legend(String[] names, int[] colors, String[] values) {
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(4), 0, dp(6));
        for (int i = 0; i < names.length; i++) {
            View swatch = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(colors[i]);
            shape.setCornerRadius(dp(2));
            swatch.setBackground(shape);
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(10), dp(10));
            swatchParams.rightMargin = dp(6);
            swatchParams.leftMargin = i == 0 ? 0 : dp(14);
            legend.addView(swatch, swatchParams);
            String label = values[i].isEmpty() ? names[i] : names[i] + " " + values[i];
            legend.addView(text(label, 12, false));
        }
        return legend;
    }

    private View row(int label, String value) {
        return row(getString(label), value);
    }

    private View row(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(text(label, 14, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView number = text(value, 14, true);
        number.setGravity(Gravity.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(12);
        row.addView(number, params);
        return row;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setUseCompatPadding(false);
        card.setRadius(dp(18));
        card.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(com.google.android.material.R.attr.colorOutline));
        return card;
    }

    private LinearLayout body(MaterialCardView card) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(body);
        return body;
    }

    private TextView title(int resource) {
        TextView view = text(getString(resource), 16, true);
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView text(String value, int sp, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(color(strong ? com.google.android.material.R.attr.colorOnSurface
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        return view;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        params.bottomMargin = dp(8);
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int seriesColor(int slot) {
        int id = slot == 1 ? R.color.chartSeries1 : slot == 2 ? R.color.chartSeries2 : R.color.chartSeries3;
        return ContextCompat.getColor(this, id);
    }

    private int gridColor() {
        int outline = color(com.google.android.material.R.attr.colorOutline);
        return (outline & 0x00FFFFFF) | 0x59000000; // 35 % of the outline: recessive gridlines.
    }

    private int color(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        if (value.resourceId != 0) return ContextCompat.getColor(this, value.resourceId);
        return value.data;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- formatting ----

    private String deviceName(String device) {
        switch (device) {
            case "builtin": return getString(R.string.dashboard_mic_builtin);
            case "bluetooth": return getString(R.string.dashboard_mic_bluetooth);
            case "wired": return getString(R.string.dashboard_mic_wired);
            case "automatic": return getString(R.string.dashboard_mic_automatic);
            default: return getString(R.string.dashboard_mic_other);
        }
    }

    private String hourLabel(int hour) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
        calendar.set(java.util.Calendar.MINUTE, 0);
        return android.text.format.DateFormat.getTimeFormat(this).format(calendar.getTime());
    }

    static String number(long value) {
        if (Math.abs(value) < 1_000) return String.valueOf(value);
        if (Math.abs(value) < 1_000_000) return trim(value / 1_000.0) + "K";
        if (Math.abs(value) < 1_000_000_000) return trim(value / 1_000_000.0) + "M";
        return trim(value / 1_000_000_000.0) + "B";
    }

    private static String trim(double value) {
        String text = String.format(Locale.getDefault(), value >= 100 ? "%.0f" : "%.1f", value);
        return text.endsWith(".0") || text.endsWith(",0") ? text.substring(0, text.length() - 2) : text;
    }

    String money(double usd) {
        if (Double.isNaN(usd)) return getString(R.string.dashboard_none);
        if (usd == 0) return "$0";
        if (usd < 0.01) return String.format(Locale.getDefault(), "$%.4f", usd);
        if (usd < 100) return String.format(Locale.getDefault(), "$%.2f", usd);
        return String.format(Locale.getDefault(), "$%,.0f", usd);
    }

    static String duration(long ms) {
        long seconds = Math.round(ms / 1000.0);
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min " + (seconds % 60) + " s";
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    private String seconds(double ms) {
        if (Double.isNaN(ms)) return getString(R.string.dashboard_none);
        return ms < 1000 ? Math.round(ms) + " ms" : decimal(ms / 1000, 1) + " s";
    }

    private String decimal(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return getString(R.string.dashboard_none);
        return String.format(Locale.getDefault(), "%." + places + "f", value);
    }

    private String percent(double ratio) {
        return String.format(Locale.getDefault(), ratio < 0.01 && ratio > 0 ? "%.2f%%" : "%.0f%%", ratio * 100);
    }

    private String decibels(double db) {
        return Double.isNaN(db) || Double.isInfinite(db) ? getString(R.string.dashboard_none)
                : decimal(db, 1) + " dBFS";
    }

    private static String plain(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }
}
