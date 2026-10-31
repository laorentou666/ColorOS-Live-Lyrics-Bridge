package io.github.andrealtb.lockscreenlyrics;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Collections;

import io.github.andrealtb.lockscreenlyrics.online.OnlineLyricSelection;

/** Uses the existing settings components and immediately persists source ordering. */
public final class OnlineLyricSettingsActivity extends SettingsBaseActivity {
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        renderRows();
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
