package io.github.andrealtb.lockscreenlyrics;

import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.andrealtb.lockscreenlyrics.online.AmllTtmlCacheProvider;
import io.github.andrealtb.lockscreenlyrics.online.OnlineLyricSelection;

public final class OnlineLyricSettingsActivity extends SettingsBaseActivity {
    private static final int[] CACHE_LIMITS_MB = {250, 500, 1000, 2000, 4000};
    private final ExecutorService cacheActions = Executors.newSingleThreadExecutor();
    private Button cacheLimit;
    private Button clearCache;
    private ArrayList<String> order;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.online_lyric_order_title);
        order = new ArrayList<>(OnlineLyricSelection.order(OnlineLyricSettings.read(this)));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = settingsScreenPadding();
        content.setPadding(padding, padding, padding, settingsScreenBottomPadding());
        content.setBackgroundColor(settingsBackgroundColor());
        installSettingsInsets(content);
        content.addView(text(getString(R.string.online_lyric_order_description), 14, 0xFF5F6368), matchWrap());
        rows = card();
        content.addView(rows, marginBottom(dp(12)));
        content.addView(cacheCard(), marginBottom(dp(12)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        renderRows();
    }

    private LinearLayout cacheCard() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.online_lyric_cache_limit), 14, settingsTextColor()), matchWrap());
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        cacheLimit = button(formatLimit(AmllTtmlCacheProvider.readLimitMb(this)));
        cacheLimit.setOnClickListener(view -> chooseCacheLimit());
        actions.addView(cacheLimit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        clearCache = button(getString(R.string.online_lyric_cache_clear));
        clearCache.setOnClickListener(view -> updateCache(0));
        actions.addView(clearCache);
        card.addView(actions, matchWrap());
        return card;
    }

    private String formatLimit(int limitMb) {
        return Formatter.formatShortFileSize(this, limitMb * 1_000_000L);
    }

    private void chooseCacheLimit() {
        int limit = AmllTtmlCacheProvider.readLimitMb(this);
        String[] labels = new String[CACHE_LIMITS_MB.length];
        int checked = -1;
        for (int i = 0; i < CACHE_LIMITS_MB.length; i++) {
            labels[i] = formatLimit(CACHE_LIMITS_MB[i]);
            if (CACHE_LIMITS_MB[i] == limit) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_lyric_cache_limit)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    updateCache(CACHE_LIMITS_MB[which]);
                })
                .show();
    }

    private void updateCache(int limitMb) {
        boolean clear = limitMb == 0;
        cacheLimit.setEnabled(false);
        clearCache.setEnabled(false);
        cacheActions.execute(() -> {
            int message = 0;
            try {
                if (clear) {
                    AmllTtmlCacheProvider.clear(getApplicationContext());
                    message = R.string.online_lyric_cache_cleared;
                } else {
                    AmllTtmlCacheProvider.setLimitMb(getApplicationContext(), limitMb);
                }
            } catch (IOException error) {
                Log.w("BridgeAmllCache", "Unable to update AMLL TTML cache", error);
                message = R.string.online_lyric_cache_error;
            }
            int feedback = message;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                cacheLimit.setText(formatLimit(AmllTtmlCacheProvider.readLimitMb(this)));
                cacheLimit.setEnabled(true);
                clearCache.setEnabled(true);
                if (feedback != 0) Toast.makeText(this, feedback, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        cacheActions.shutdown();
        super.onDestroy();
    }

    private void renderRows() {
        rows.removeAllViews();
        for (int index = 0; index < order.size(); index++) {
            final int position = index;
            String source = order.get(index);
            int label = "netease".equals(source) ? R.string.player_netease
                    : "qqmusic".equals(source) ? R.string.player_qq
                    : "kugou".equals(source) ? R.string.player_kugou : R.string.online_lyric_amll;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text((index + 1) + ". " + getString(label), 14, settingsTextColor()),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button up = button(getString(R.string.online_lyric_move_up));
            up.setEnabled(index > 0);
            up.setContentDescription(getString(label) + " " + getString(R.string.online_lyric_move_up));
            up.setOnClickListener(view -> move(position, position - 1));
            row.addView(up);
            Button down = button(getString(R.string.online_lyric_move_down));
            down.setEnabled(index + 1 < order.size());
            down.setContentDescription(getString(label) + " " + getString(R.string.online_lyric_move_down));
            down.setOnClickListener(view -> move(position, position + 1));
            row.addView(down);
            rows.addView(row, matchWrap());
        }
    }

    private void move(int from, int to) {
        Collections.swap(order, from, to);
        OnlineLyricSettings.save(this, String.join(",", order));
        OnlineLyricSettings.publish(this);
        renderRows();
    }
}
