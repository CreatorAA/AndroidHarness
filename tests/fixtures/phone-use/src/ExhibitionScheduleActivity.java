package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Synthetic exhibition program. The cutoff is deliberately absent from the list and its
 * accessibility labels. It only exists in the chosen session's admissions panel. */
public final class ExhibitionScheduleActivity extends Activity {
    static final int TARGET=2;
    private final String[] times={"周六 14:00—18:00","周日 09:00—12:00","周日 14:00—18:00"};
    private final String[] rooms={"东馆 · 临街展厅","北馆 · 一层小厅","南馆 · 长廊展厅"};
    private static final int INK=0xff182a2b, MUTED=0xff4b6768, TEAL=0xff165f5b;
    volatile int targetOpens,otherOpens,stars;
    volatile boolean cutoffRendered;
    String cutoff;
    private int showing=-1;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state==null){ThreadLocalRandom random=ThreadLocalRandom.current();
            cutoff=String.format(Locale.ROOT,"%02d:%02d",random.nextInt(16,18),random.nextInt(5,58));
        }else{cutoff=state.getString("cutoff");showing=state.getInt("page",-1);targetOpens=state.getInt("target");
            otherOpens=state.getInt("other");stars=state.getInt("stars");cutoffRendered=state.getBoolean("rendered");}
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putString("cutoff",cutoff);out.putInt("page",showing);out.putInt("target",targetOpens);out.putInt("other",otherOpens);
        out.putInt("stars",stars);out.putBoolean("rendered",cutoffRendered);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable round(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private TextView text(String body,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(body);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private void space(LinearLayout parent,int h){parent.addView(new View(this),1,dp(h));}
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xfff3f3ed);
        LinearLayout page=column();page.setPadding(dp(18),dp(22),dp(18),dp(20));scroll.addView(page);setContentView(scroll);
        page.addView(text("山岸艺术馆  /  本周",14,TEAL,true));space(page,8);
        if(showing<0){
            page.addView(text("周末展览日程",29,INK,true));space(page,4);
            page.addView(text("摄影系列 · 三个场次",16,MUTED,false));space(page,18);
            for(int i=0;i<times.length;i++)session(page,i);
        }else{
            final int slot=showing;page.addView(text("河岸来信",29,INK,true));space(page,6);
            page.addView(text("摄影展    "+times[slot],18,TEAL,true));space(page,5);
            page.addView(text(rooms[slot],17,MUTED,false));space(page,24);
            LinearLayout admission=column();admission.setPadding(dp(16),dp(16),dp(16),dp(20));admission.setBackground(round(Color.WHITE,13));page.addView(admission,-1,-2);
            admission.addView(text("入场安排",18,INK,true));space(admission,8);
            TextView line=text("停止入场："+(slot==TARGET?cutoff:slot==0?"17:20":"11:40"),23,TEAL,true);admission.addView(line);
            if(slot==TARGET)line.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                public boolean onPreDraw(){Rect actual=new Rect();if(line.getGlobalVisibleRect(actual)&&actual.height()>=line.getHeight()/2&&!actual.isEmpty()){
                    cutoffRendered=true;line.getViewTreeObserver().removeOnPreDrawListener(this);}return true;}
            });
            space(admission,8);admission.addView(text("闭馆前可以在展厅内继续参观。",15,MUTED,false));
            space(page,20);Button back=new Button(this);back.setText("返回日程");back.setOnClickListener(v->{showing=-1;render();});page.addView(back,-2,-2);
        }
    }
    private void session(LinearLayout page,final int i){
        LinearLayout group=new LinearLayout(this);group.setGravity(Gravity.CENTER_VERTICAL);page.addView(group,-1,-2);
        LinearLayout row=column();row.setPadding(dp(15),dp(11),dp(12),dp(11));row.setBackground(round(Color.WHITE,12));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setOnClickListener(v->{if(i==TARGET)targetOpens++;else otherOpens++;showing=i;render();});
        group.addView(row,new LinearLayout.LayoutParams(0,-2,1));
        // Two inert layout levels before the visible title; all three sessions share its exact
        // wording. Their day/time and venue, not a hidden ID or a special command, identify one.
        LinearLayout body=column(),heading=column();
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.addView(body,-1,-2);body.addView(heading,-1,-2);
        heading.addView(text("河岸来信",19,INK,true));body.addView(text("摄影展  ·  "+times[i],15,TEAL,true));
        body.addView(text(rooms[i],14,MUTED,false));
        ImageButton star=new ImageButton(this);star.setBackground(round(0xffdee8df,24));star.setImageResource(android.R.drawable.btn_star_big_off);
        star.setPadding(dp(9),dp(9),dp(9),dp(9));star.setScaleType(ImageView.ScaleType.FIT_CENTER);
        star.setContentDescription("收藏："+times[i]+"的展览");
        star.setOnClickListener(v->{stars++;star.setImageResource(android.R.drawable.btn_star_big_on);});
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(44),dp(44));size.leftMargin=dp(8);group.addView(star,size);space(page,9);
    }
    @Override public void onBackPressed(){if(showing>=0){showing=-1;render();}else super.onBackPressed();}
}
