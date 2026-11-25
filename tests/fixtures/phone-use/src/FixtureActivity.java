package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Independent target. The only side effect is a counter in this fixture's own preferences. */
public final class FixtureActivity extends Activity {
    private LinearLayout column;
    private String message = "", choice = "";
    private int page = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            page = state.getInt("page", 1);
            message = state.getString("message", "");
            choice = state.getString("choice", "");
        }
        render();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(int id, String text, int size) {
        TextView v = new TextView(this);
        if (id != 0) v.setId(id);
        v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(28, 38, 48));
        v.setPadding(0, dp(8), 0, dp(8));
        // Per-page swatches make it possible to distinguish current pixels from an old receipt or black capture.
        if (id == R.id.page_title) v.setBackgroundColor(page == 1 ? 0xff65c6ad : page == 2 ? 0xffffc55c : 0xffaf98e3);
        column.addView(v);
        return v;
    }
    private void button(int id, String text, View.OnClickListener action) {
        Button b = new Button(this); b.setId(id); b.setText(text);
        b.setOnClickListener(action); column.addView(b);
    }
    private void rememberMessage() {
        EditText v = findViewById(R.id.message); if (v != null) message = v.getText().toString();
    }
    private void render() {
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true);
        column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(24)); column.setFocusableInTouchMode(true);
        scroller.addView(column); setContentView(scroller);
        if (page == 1) {
            label(R.id.page_title, "第一步 · 填写留言", 25);
            label(0, "提交次数：" + count(), 15);
            EditText input = new EditText(this); input.setId(R.id.message);
            input.setHint("留言内容"); input.setSingleLine(false); input.setText(message); column.addView(input);
            label(0, "上方选项：青竹", 17); button(R.id.choose_first, "选择", v -> review("青竹"));
            label(0, "下方选项：白鹭", 17); button(R.id.choose_second, "选择", v -> review("白鹭"));
        } else if (page == 2) {
            label(R.id.page_title, "第二步 · 核对", 25);
            label(R.id.review, "选项：" + choice + "\n留言：" + message, 20);
            button(R.id.submit, "确认提交", v -> {
                int next = count() + 1;
                if (!getPreferences(MODE_PRIVATE).edit().putInt("count", next).commit()) return;
                page = 3; render();
            });
            button(R.id.edit_review, "返回修改", v -> { page = 1; render(); });
        } else {
            label(R.id.page_title, "第三步 · 已收到", 25);
            label(R.id.receipt, "提交次数：" + count() + "\n选项：" + choice + "\n留言：" + message, 20);
            button(R.id.again, "返回填写", v -> { page = 1; render(); });
        }
    }
    private int count() { return getPreferences(MODE_PRIVATE).getInt("count", 0); }
    private void review(String value) {
        rememberMessage(); choice = value;
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        page = 2; render();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        rememberMessage(); out.putInt("page", page); out.putString("message", message); out.putString("choice", choice);
    }
    @Override public void onBackPressed() {
        if (page == 2 || page == 3) { page = 1; render(); } else super.onBackPressed();
    }
}
