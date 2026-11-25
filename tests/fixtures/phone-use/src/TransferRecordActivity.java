package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Locale;

/** Synthetic detail view. The same photograph supplies the cropped preview and original.
 * The newly assigned registration number is only painted on the bottom of the original. */
public final class TransferRecordActivity extends Activity {
    static final int W=800,H=500;
    private String number;private int opens,decor;private boolean viewer;
    private Bitmap photo,preview;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state==null) number=String.format(Locale.ROOT,"%04d",new SecureRandom().nextInt(10000));
        else {number=state.getString("number");viewer=state.getBoolean("viewer");opens=state.getInt("opens");decor=state.getInt("decor");}
        photo=photograph();
        // This actual crop shows the field site's illustration/heading, not the register field.
        preview=Bitmap.createBitmap(photo,0,0,W,235);
        render();
    }
    @Override protected void onSaveInstanceState(Bundle b){
        super.onSaveInstanceState(b);b.putString("number",number);b.putBoolean("viewer",viewer);b.putInt("opens",opens);b.putInt("decor",decor);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void render(){
        LinearLayout p=new LinearLayout(this);p.setOrientation(1);setContentView(p);
        if(viewer){
            p.setBackgroundColor(0xff222c2c);p.setGravity(Gravity.CENTER);
            ImageView full=new ImageView(this);full.setImageBitmap(photo);full.setScaleType(ImageView.ScaleType.FIT_XY);
            full.setContentDescription("交接单照片");full.setAdjustViewBounds(false);
            int w=getResources().getDisplayMetrics().widthPixels;p.addView(full,-1,w*H/W);
            return;
        }
        p.setBackgroundColor(0xffeff2f0);
        TextView header=label("样本交接记录",22,0xff243831);header.setTypeface(null,Typeface.BOLD);
        header.setBackgroundColor(0xffffffff);header.setPadding(dp(20),dp(17),dp(20),dp(15));p.addView(header,-1,-2);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(17),dp(20),dp(20),0);p.addView(body);
        TextView date=label("外业档案     /     河湾工作站",13,0xff62776e);date.setGravity(Gravity.START);body.addView(date,-1,-2);
        LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(19),0,0);body.addView(row);
        ImageButton avatar=new ImageButton(this);avatar.setContentDescription("值班员头像");avatar.setImageResource(android.R.drawable.ic_menu_myplaces);
        avatar.setBackground(bg(0xffb6c9bd,12));avatar.setOnClickListener(v->decor++);row.addView(avatar,dp(40),dp(40));
        LinearLayout thread=new LinearLayout(this);thread.setOrientation(1);thread.setPadding(dp(10),0,0,0);row.addView(thread,-1,-2);
        thread.addView(label("材料说明",14,0xff63796f));
        TextView msg=label("河湾批次已完成交接。附件保存的是本次登记单。",17,0xff263b33);
        msg.setPadding(dp(13),dp(9),dp(13),dp(9));msg.setBackground(bg(0xffffffff,9));thread.addView(msg);
        ImageView thumb=new ImageView(this);thumb.setImageBitmap(preview);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setContentDescription("图片");thumb.setOnClickListener(v->{opens++;viewer=true;render();});
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(166),dp(94));size.topMargin=dp(8);thread.addView(thumb,size);
        TextView caption=label("河湾样本交接单",14,0xff5b7065);caption.setPadding(0,dp(5),0,0);thread.addView(caption);
    }
    @Override public void onBackPressed(){if(viewer){viewer=false;render();}else super.onBackPressed();}
    @Override public void dump(String prefix,FileDescriptor fd,PrintWriter writer,String[] args){
        super.dump(prefix,fd,writer,args);
        // Fixture-only terminal oracle; the model never sees dumpsys. No answer is emitted.
        boolean match=false;if(opens>0&&args!=null)for(String a:args)if(("check-"+number).equals(a))match=true;
        writer.println(prefix+"photoFixture: opened="+opens+" decoration="+decor+" answerMatches="+match);
    }
    private Bitmap photograph(){
        Bitmap image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);c.drawColor(0xfff8f3dc);
        p.setColor(0xffc1d7c8);c.drawRect(0,0,W,145,p);p.setColor(0xff648a72);
        Path ridge=new Path();ridge.moveTo(0,132);ridge.lineTo(122,83);ridge.lineTo(237,116);ridge.lineTo(374,48);
        ridge.lineTo(448,89);ridge.lineTo(577,62);ridge.lineTo(800,110);ridge.lineTo(800,145);ridge.lineTo(0,145);ridge.close();c.drawPath(ridge,p);
        p.setColor(0xfff8f3dc);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(24);c.drawText("RIVER FIELD STATION",34,124,p);
        p.setColor(0xff254838);p.setTextSize(41);c.drawText("河湾样本交接单",40,210,p);
        p.setStrokeWidth(2);c.drawLine(40,234,760,234,p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(27);c.drawText("样本登记号",40,291,p);
        // Seven-segment display printed into the photograph, not exposed as text or metadata.
        int[] masks={119,36,93,109,46,107,123,37,127,111};
        int[][] bars={{7,0,57,11},{0,8,11,54},{53,8,64,54},{7,51,57,62},{0,59,11,105},{53,59,64,105},{7,102,57,113}};
        for(int i=0;i<4;i++){int mask=masks[number.charAt(i)-'0'];int x=58+i*91;
            for(int j=0;j<bars.length;j++)if((mask&(1<<j))!=0){int[] b=bars[j];c.drawRoundRect(x+b[0],318+b[1],x+b[2],318+b[3],3,3,p);}}
        p.setTextSize(17);p.setColor(0xff617c6b);c.drawText("FIELD COPY   /   TRANSFER REGISTER",42,472,p);
        return image;
    }
}
