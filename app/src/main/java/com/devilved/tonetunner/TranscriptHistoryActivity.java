package com.devilved.tonetunner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.devilved.tonetunner.asr.TranscriptHistoryStore;
import com.devilved.tonetunner.utils.ThemeUtils;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Paged, copy-first UI for locally stored transcripts. */
public final class TranscriptHistoryActivity extends AppCompatActivity {
    private static final int PAGE_SIZE = 20;
    private static final int PREVIEW_CODE_POINT_LIMIT = 1_500;

    private final List<TranscriptHistoryStore.Summary> entries = new ArrayList<>();
    private final ExecutorService historyIo = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ListView historyList;
    private Button loadMoreButton;
    private ProgressBar progress;
    private TextView emptyView;
    private TextView errorView;
    private SummaryAdapter adapter;
    private boolean loading;
    private boolean hasMore;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcript_history);
        ThemeUtils.setStatusBarAppearance(this);

        historyList = findViewById(R.id.transcript_history_list);
        loadMoreButton = findViewById(R.id.transcript_history_load_more);
        progress = findViewById(R.id.transcript_history_progress);
        emptyView = findViewById(R.id.transcript_history_empty);
        errorView = findViewById(R.id.transcript_history_error);
        adapter = new SummaryAdapter();
        historyList.setAdapter(adapter);
        historyList.setOnItemClickListener((parent, view, position, id) -> {
            if (!loading && position >= 0 && position < entries.size()) openEntry(entries.get(position));
        });
        loadMoreButton.setOnClickListener(view -> loadPage(false));
        loadPage(true);
    }

    @Override protected void onDestroy() {
        historyIo.shutdownNow();
        super.onDestroy();
    }

    private void loadPage(boolean refresh) {
        if (loading) return;
        if (refresh) {
            entries.clear();
            hasMore = false;
            adapter.notifyDataSetChanged();
        }
        loading = true;
        progress.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        loadMoreButton.setEnabled(false);
        int offset = entries.size();
        submitIo(() -> {
            List<TranscriptHistoryStore.Summary> page = null;
            IOException failure = null;
            try (TranscriptHistoryStore store = new TranscriptHistoryStore(getApplicationContext())) {
                page = store.list(PAGE_SIZE + 1, offset);
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new IOException("Transcript history storage is unavailable.", e);
            }
            List<TranscriptHistoryStore.Summary> result = page;
            IOException error = failure;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                progress.setVisibility(View.GONE);
                loadMoreButton.setEnabled(true);
                if (error != null || result == null) {
                    errorView.setText(R.string.transcript_history_error);
                    errorView.setVisibility(View.VISIBLE);
                    loadMoreButton.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
                    return;
                }
                hasMore = result.size() > PAGE_SIZE;
                int count = Math.min(PAGE_SIZE, result.size());
                for (int i = 0; i < count; i++) entries.add(result.get(i));
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                loadMoreButton.setVisibility(hasMore ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void openEntry(TranscriptHistoryStore.Summary summary) {
        loading = true;
        progress.setVisibility(View.VISIBLE);
        submitIo(() -> {
            TranscriptHistoryStore.Entry entry = null;
            IOException failure = null;
            try (TranscriptHistoryStore store = new TranscriptHistoryStore(getApplicationContext())) {
                entry = store.get(summary.id);
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new IOException("Transcript history storage is unavailable.", e);
            }
            TranscriptHistoryStore.Entry result = entry;
            IOException error = failure;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                progress.setVisibility(View.GONE);
                if (error != null || result == null) {
                    errorView.setText(R.string.transcript_history_error);
                    errorView.setVisibility(View.VISIBLE);
                    if (error == null) loadPage(true);
                    return;
                }
                showEntry(result);
            });
        });
    }

    private void showEntry(TranscriptHistoryStore.Entry entry) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = getResources().getDimensionPixelSize(R.dimen.transcript_history_dialog_padding);
        content.setPadding(side, side, side, 0);

        TextView metadata = new TextView(this);
        String date = DateFormat.getDateTimeInstance().format(new Date(entry.createdAt));
        String language = TextUtils.isEmpty(entry.language)
                ? getString(R.string.transcript_history_language, getString(R.string.auto_lang))
                : getString(R.string.transcript_history_language, entry.language);
        metadata.setText(date + "\n" + language
                + (entry.isComplete ? "" : "\n" + getString(R.string.transcript_history_incomplete))
                + (TextUtils.isEmpty(entry.warning) ? "" : "\n"
                + getString(R.string.transcript_history_warning, entry.warning)));
        content.addView(metadata);
        RadioGroup textChoice = new RadioGroup(this);
        textChoice.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton resultChoice = new RadioButton(this);
        resultChoice.setId(View.generateViewId());
        resultChoice.setText(R.string.transcript_history_result_label);
        textChoice.addView(resultChoice);
        RadioButton rawChoice = null;
        if (!entry.rawText.equals(entry.text)) {
            rawChoice = new RadioButton(this);
            rawChoice.setId(View.generateViewId());
            rawChoice.setText(R.string.transcript_history_raw_label);
            textChoice.addView(rawChoice);
        }
        content.addView(textChoice);
        TextView fullText = new TextView(this);
        fullText.setTextIsSelectable(true);
        fullText.setText(entry.text);
        fullText.setPadding(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.transcript_history_section_gap));
        content.addView(fullText);
        final int rawChoiceId = rawChoice == null ? View.NO_ID : rawChoice.getId();
        textChoice.setOnCheckedChangeListener((group, checkedId) ->
                fullText.setText(checkedId == rawChoiceId ? entry.rawText : entry.text));
        resultChoice.setChecked(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.transcript_history_detail)
                .setView(scroll)
                .setPositiveButton(R.string.transcript_history_copy_result,
                        (which, button) -> copyText(entry.text,
                                getString(R.string.transcript_history_result_label)))
                .setNeutralButton(R.string.transcript_history_copy_raw,
                        (which, button) -> copyText(entry.rawText,
                                getString(R.string.transcript_history_raw_label)))
                .setNegativeButton(R.string.transcript_history_delete,
                        (which, button) -> confirmDelete(entry, null))
                .create();
        dialog.show();
    }

    private void confirmDelete(TranscriptHistoryStore.Entry entry, AlertDialog detailDialog) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.transcript_history_delete)
                .setMessage(R.string.transcript_history_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_button, (dialog, which) -> {
                    if (detailDialog != null) detailDialog.dismiss();
                    loading = true;
                    progress.setVisibility(View.VISIBLE);
                    submitIo(() -> {
                        IOException failure = null;
                        try (TranscriptHistoryStore store = new TranscriptHistoryStore(getApplicationContext())) {
                            store.delete(entry.id);
                        } catch (IOException e) {
                            failure = e;
                        } catch (RuntimeException e) {
                            failure = new IOException("Transcript history storage is unavailable.", e);
                        }
                        IOException error = failure;
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            loading = false;
                            progress.setVisibility(View.GONE);
                            if (error != null) {
                                errorView.setText(R.string.transcript_history_error);
                                errorView.setVisibility(View.VISIBLE);
                                return;
                            }
                            loadPage(true);
                        });
                    });
                })
                .show();
    }

    private void copyText(String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(this, R.string.transcript_history_copied, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.transcript_history_copy_failed, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, R.string.transcript_history_copy_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void submitIo(Runnable task) {
        try {
            historyIo.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The Activity is closing; do not access storage or show stale UI after shutdown.
        }
    }

    private final class SummaryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(
                    TranscriptHistoryActivity.this);
            TranscriptHistoryStore.Summary item = entries.get(position);
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(item.createdAt));
            row.setText(date + (TextUtils.isEmpty(item.language) ? "" : " · " + item.language)
                    + "\n" + item.preview);
            row.setTextSize(16);
            row.setMaxLines(3);
            row.setEllipsize(TextUtils.TruncateAt.END);
            int horizontal = getResources().getDimensionPixelSize(R.dimen.transcript_history_row_horizontal_padding);
            int vertical = getResources().getDimensionPixelSize(R.dimen.transcript_history_row_vertical_padding);
            row.setPadding(horizontal, vertical, horizontal, vertical);
            return row;
        }
    }
}
