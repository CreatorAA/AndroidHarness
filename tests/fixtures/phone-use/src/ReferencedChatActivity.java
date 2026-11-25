package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ThreadLocalRandom;

/** Local, synthetic conversation. Only opening the forwarded card reveals the model and the find. */
public final class ReferencedChatActivity extends Activity {
    private LinearLayout messages;
    private boolean detail;
    volatile boolean evidenceShown;
    volatile int opens, sends, neighborTaps;
    String deviceModel;

    /** Test-only device evidence, independent of what the assistant reports. */
    @Override public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        super.dump(prefix, fd, out, args);
        out.println(prefix + "fixtureCounts: opens=" + opens + " neighbors=" + neighborTaps + " sends=" + sends);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        deviceModel = state == null ? "HX-" + ThreadLocalRandom.current().nextInt(1000, 10000) : state.getString("model");
        detail = state != null && state.getBoolean("detail");
        if (state != null) { opens = state.getInt("opens"); sends = state.getInt("sends"); neighborTaps=state.getInt("neighbor"); evidenceShown = state.getBoolean("shown"); }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b); b.putString("model",deviceModel); b.putBoolean("detail",detail);
        b.putInt("opens",opens); b.putInt("sends",sends); b.putInt("neighbor",neighborTaps); b.putBoolean("shown", evidenceShown);
    }
    @Override public void onBackPressed() { if (detail) { detail=false; render(); } else super.onBackPressed(); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String body, int size, int color) {
        TextView t = new TextView(this); t.setText(body); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private void speaker(String name) {
        TextView t=text(name,13,0xff50636b); t.setPadding(0,dp(14),0,dp(4)); messages.addView(t);
    }
    private TextView bubble(String value) {
        TextView t=text(value,17,0xff1d2b32); t.setPadding(dp(13),dp(9),dp(13),dp(9));
        t.setBackground(bg(Color.WHITE,11)); messages.addView(t,-2,-2); return t;
    }
    private void render() {
        LinearLayout page=new LinearLayout(this); page.setOrientation(1); page.setBackgroundColor(0xffedf1f2);
        setContentView(page);
        TextView header=text(detail ? "群聊的聊天记录" : "掌中设备修复小组（4）",22,0xff18292e);
        header.setTypeface(null,Typeface.BOLD); header.setPadding(dp(20),dp(18),dp(16),dp(13));
        header.setBackgroundColor(Color.WHITE); page.addView(header,-1,-2);
        ScrollView scroll = new ScrollView(this); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        messages=new LinearLayout(this); messages.setOrientation(1); messages.setPadding(dp(20),0,dp(20),dp(14));
        scroll.addView(messages);
        if (!detail) {
            speaker("许蓝"); bubble("今天的讲座改成线上了。");
            speaker("阿拓"); bubble("好，时间不变。");
            speaker("谷雨");
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.TOP);messages.addView(row,-1,-2);
            ImageButton avatar=new ImageButton(this);avatar.setId(R.id.v9a);
            avatar.setBackground(bg(0xff426b75,24));avatar.setImageResource(android.R.drawable.ic_menu_myplaces);
            avatar.setColorFilter(Color.WHITE);avatar.setPadding(dp(9),dp(9),dp(9),dp(9));
            avatar.setContentDescription("谷雨的头像");avatar.setOnClickListener(v->neighborTaps++);
            row.addView(avatar,dp(42),dp(42));
            // Actual forwarding cards expose the title on a non-clickable child. Its unnamed,
            // clickable direct parent also contains preview text; the avatar is a separate target.
            LinearLayout card = new LinearLayout(this);card.setId(R.id.c4q);card.setOrientation(1);
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            card.setBackground(bg(Color.WHITE,10));card.setPadding(dp(14),dp(10),dp(16),dp(12));
            TextView title=text("群聊的聊天记录",17,0xff1b4137);
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            card.addView(title,-1,-2);
            TextView summary=text("小岑：我回去又翻了一遍备份。\n谷雨：是上次说的那个吗？",14,0xff526861);
            summary.setPadding(0,dp(5),0,0);card.addView(summary,-1,-2);
            card.setOnClickListener(v->{opens++;detail=true;render();});
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,-2);size.leftMargin=dp(9);row.addView(card,size);
            View gap=new View(this); messages.addView(gap,1,dp(5));
            TextView reaction=bubble("真有啊！");LinearLayout.LayoutParams inset=(LinearLayout.LayoutParams)reaction.getLayoutParams();
            inset.leftMargin=dp(51);reaction.setLayoutParams(inset);
            LinearLayout composer = new LinearLayout(this); composer.setGravity(Gravity.CENTER_VERTICAL);
            composer.setPadding(dp(12),dp(6),dp(12),dp(6)); composer.setBackgroundColor(Color.WHITE);
            EditText draft=new EditText(this); draft.setSingleLine(); draft.setHint("输入消息");
            composer.addView(draft,new LinearLayout.LayoutParams(0,-2,1));
            Button send=new Button(this); send.setText("发送"); send.setOnClickListener(v->{sends++;draft.setText("");});
            composer.addView(send); page.addView(composer,-1,-2);
            // Don't enter the chat editor (or open the keyboard) on launch.
            page.setFocusableInTouchMode(true); page.requestFocus();
        } else {
            speaker("小岑"); bubble("那个维修资料仓库我去年就收藏过了。");
            speaker("谷雨"); bubble("仓库我也知道。我找了好几年，以为这款旧阅读器的字库早就失传了。");
            speaker("小岑");
            TextView fact=bubble("刚核过备份：里面真的保留着 " + deviceModel + " 阅读器的原厂离线点阵字库，不只是使用手册。");
            fact.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    Rect r=new Rect(); if(fact.getGlobalVisibleRect(r) && !r.isEmpty()) {
                        evidenceShown=true; fact.getViewTreeObserver().removeOnPreDrawListener(this);
                    } return true;
                }
            });
            Button back=new Button(this); back.setText("返回群聊"); back.setOnClickListener(v->{detail=false;render();});
            page.addView(back,-1,-2);
        }
    }
}
