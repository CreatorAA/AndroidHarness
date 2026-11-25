package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;

/** A real diagram, not labelled swatches. Nothing in the view tree, a resource ID, or the
 * program text specifies this visit's four landmarks. Test expectations stay in memory. */
public final class VisualRouteActivity extends Activity {
    static final String BOARD="河岸步道示意图";
    static final int[] PALETTE={0xffcd4038,0xff267d49,0xff305eae,0xffdca80d};
    static final String[] COLORS={"红","绿","蓝","黄"},SHAPES={"圆形","正方形","三角形","五角星"};
    int[] marks;volatile boolean drawn;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);marks=saved==null?null:saved.getIntArray("map");
        if(marks==null){ArrayList<Integer> deck=new ArrayList<>();for(int i=0;i<16;i++)deck.add(i);
            Collections.shuffle(deck,new SecureRandom());marks=new int[4];for(int i=0;i<4;i++)marks[i]=deck.get(i);}
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(18),dp(21),dp(18),dp(14));
        page.setBackgroundColor(0xfff4f2e9);setContentView(page);
        label(page,"野外手册    /    河谷段",14,0xff476660,false);label(page,"河岸步道",31,0xff203b39,true);
        TextView sub=label(page,"从起点沿箭头走到终点。",16,0xff465a55,false);sub.setPadding(0,dp(3),0,dp(12));
        MapBoard board=new MapBoard();board.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        board.setContentDescription(BOARD);page.addView(board,-1,-2);
        TextView note=label(page,"步行约 20 分钟    ·    全程步道",14,0xff53655e,false);note.setPadding(0,dp(13),0,0);
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putIntArray("map",marks);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(LinearLayout p,String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(t);return t;
    }
    final class MapBoard extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);MapBoard(){super(VisualRouteActivity.this);}
        @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,Math.min(Math.round(width*.69f),dp(260)));}
        void fill(int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);}
        void arrow(Canvas c,float x,float y,int dir,float r){Path p=new Path();
            if(dir==0){p.moveTo(x-r,y-r*.65f);p.lineTo(x+r,y);p.lineTo(x-r,y+r*.65f);}
            else if(dir==1){p.moveTo(x-r*.65f,y-r);p.lineTo(x,y+r);p.lineTo(x+r*.65f,y-r);}
            else{p.moveTo(x+r,y-r*.65f);p.lineTo(x-r,y);p.lineTo(x+r,y+r*.65f);}p.close();fill(0xff415852);c.drawPath(p,paint);}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),round=dp(17),r=Math.min(w,h)*.115f;
            fill(0xfffcfaf0);c.drawRoundRect(0,0,w,h,round,round,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(6));paint.setColor(0xffd9e8df);
            Path river=new Path();river.moveTo(w*.02f,h*.66f);river.cubicTo(w*.32f,h*.38f,w*.48f,h*.80f,w*.99f,h*.48f);c.drawPath(river,paint);
            float l=w*.25f,q=w*.75f,t=h*.29f,b=h*.73f;Path route=new Path();route.moveTo(l,t);route.lineTo(q,t);route.lineTo(q,b);route.lineTo(l,b);
            paint.setStrokeWidth(dp(2));paint.setColor(0xff64746b);c.drawPath(route,paint);
            arrow(c,w*.51f,t,0,dp(5));arrow(c,q,h*.52f,1,dp(5));arrow(c,w*.50f,b,2,dp(5));
            float[] x={l,q,q,l},y={t,t,b,b};
            for(int i=0;i<4;i++){c.save();c.translate(x[i],y[i]);fill(PALETTE[marks[i]/4]);
                int s=marks[i]%4;Path p=new Path();
                if(s==0)c.drawCircle(0,0,r,paint);
                else if(s==1)c.drawRect(-r,-r,r,r,paint);
                else if(s==2){p.moveTo(0,-r);p.lineTo(r,r);p.lineTo(-r,r);p.close();c.drawPath(p,paint);}
                else{for(int j=0;j<10;j++){double a=-Math.PI/2+j*Math.PI/5;float radius=j%2==0?r:r*.44f;
                    float aX=(float)Math.cos(a)*radius,aY=(float)Math.sin(a)*radius;if(j==0)p.moveTo(aX,aY);else p.lineTo(aX,aY);}
                    p.close();c.drawPath(p,paint);}
                c.restore();}
            fill(0xff344a40);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(dp(13));paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("起点",l,h*.105f,paint);c.drawText("终点",l,h*.945f,paint);drawn=true;
        }
    }
}
