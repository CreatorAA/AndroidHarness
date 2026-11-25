package com.androidharness.fixture.phoneuse;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Black-box trial against the phone's existing model. An exact four-marker answer supplies
 * real behavioral evidence; only the isolated emulator provider can inspect on-wire pixels. */
public final class VisualRouteTrial extends ReadPageProbe{
    private static final String PKG="com.androidharness.fixture.phoneuse";
    private static final String REQUEST="帮我在这页路线图里查一下，从起点到终点依次经过哪四个路标？按顺序告诉我每个路标的颜色和形状。";
    private static final String COL="(红|绿|蓝|黄)(?:色)?",SH="(圆形|圆点|圆圈|圆盘|圆|正方形|正方块|方块|方形|三角形|三角|五角星形|五角星|星形|星星|星)";
    private static final String JOIN="[\\s的·:：,，/（）()\\-]{0,6}";
    private static final Pattern PAIR=Pattern.compile(COL+JOIN+SH+"|"+SH+JOIN+COL);
    private boolean live,submitted,ended;
    private final Bundle out=new Bundle();
    @Override public void onCreate(Bundle a){live="one-explicit-device-trial".equals(a.getString("live"));super.onCreate(a);}
    private static String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private String foreground(){if(Build.VERSION.SDK_INT>=33)ui.clearCache();AccessibilityNodeInfo n=ui.getRootInActiveWindow();return n==null?"":String.valueOf(n.getPackageName());}
    private void walk(AccessibilityNodeInfo n,String pkg,List<AccessibilityNodeInfo> list){
        if(n==null||!pkg.contentEquals(n.getPackageName()))return;if(n.isVisibleToUser())list.add(n);
        for(int k=0;k<n.getChildCount();k++)walk(n.getChild(k),pkg,list);
    }
    private boolean source(){
        if(!PKG.equals(foreground()))return false;List<AccessibilityNodeInfo> tree=new ArrayList<>();walk(ui.getRootInActiveWindow(),PKG,tree);
        boolean diagram=false;
        for(AccessibilityNodeInfo n:tree){diagram|=label(n,VisualRouteActivity.BOARD);
            if(PAIR.matcher(text(n)).find()||PAIR.matcher(String.valueOf(n.getContentDescription())).find())throw new IllegalStateException("Source tree discloses a landmark");}
        return diagram;
    }
    private void ask()throws Exception{
        command("input keyevent KEYCODE_ASSIST");if(waitNode(n->n.isEditable()||label(n,"改用键盘","新问题"),3200)==null){
            if(!PKG.equals(foreground()))throw new IllegalStateException("Source lost");command("cmd voiceinteraction show");}
        AccessibilityNodeInfo f=waitNode(AccessibilityNodeInfo::isEditable,250);
        if(f==null){click(waitNode(n->label(n,"改用键盘","Type instead"),3000));f=waitNode(AccessibilityNodeInfo::isEditable,3000);}
        if(f==null||!f.isShowingHintText()&&!text(f).isEmpty()&&!label(f,"继续追问","问问这个屏幕"))throw new IllegalStateException("No empty composer");
        Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,REQUEST);
        if(!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)||waitNode(n->n.isEditable()&&text(n).equals(REQUEST),1400)==null)throw new IllegalStateException("Could not enter question");
        click(waitNode(n->label(n,"发送","Send"),2200));submitted=true;
    }
    private static boolean tuple(String s,int[] expected){
        List<Integer> found=new ArrayList<>();Matcher m=PAIR.matcher(s.replace("*",""));
        while(m.find()){String color=m.group(1)!=null?m.group(1):m.group(4),shape=m.group(2)!=null?m.group(2):m.group(3);
            int c="红绿蓝黄".indexOf(color),sh=shape.contains("星")?3:shape.contains("三角")?2:shape.contains("方")?1:0;found.add(c*4+sh);}
        if(found.size()!=4)return false;
        for(int i=0;i<4;i++)if(found.get(i)!=expected[i])return false;return true;
    }
    private boolean answer(List<AccessibilityNodeInfo> visible,int[] expected){
        int anchor=-1;for(int i=0;i<visible.size();i++)if(!visible.get(i).isEditable()&&text(visible.get(i)).equals(REQUEST))anchor=i;
        if(anchor<0)return false;
        StringBuilder body=new StringBuilder();for(int i=anchor+1;i<visible.size();i++){
            AccessibilityNodeInfo n=visible.get(i);String s=text(n);if(n.isEditable()||s.isEmpty()||s.length()>2000||s.contains("{")||s.contains("observationId"))continue;
            if(tuple(s,expected))return true;
            if(n.getChildCount()==0&&body.length()<3000)body.append(s).append('\n');
        }
        return tuple(body.toString(),expected);
    }
    @Override public void onStart(){
        VisualRouteActivity fixture=null;
        try{
            if(!live||!PKG.equals(target)||!"com.androidharness.app".equals(harness))throw new IllegalArgumentException("Requires explicit real model fixture opt-in");
            if(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Device locked");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);AccessibilityServiceInfo i=ui.getServiceInfo();
            i.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(i);
            String current=foreground();ComponentName home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).resolveActivity(getTargetContext().getPackageManager());
            if(!PKG.equals(current)&&!harness.equals(current)&&!"com.miui.home".equals(current)&&(home==null||!home.getPackageName().equals(current)))throw new IllegalStateException("Phone in another app; no switch");
            ActivityMonitor monitor=addMonitor(VisualRouteActivity.class.getName(),null,false);
            try{command("am start -W -f 0x10008000 -n "+PKG+"/.VisualRouteActivity");fixture=(VisualRouteActivity)waitForMonitorWithTimeout(monitor,4500);}finally{removeMonitor(monitor);}
            if(fixture==null)throw new IllegalStateException("No route activity");
            long r=SystemClock.elapsedRealtime()+3500;while(!(fixture.drawn&&source())&&SystemClock.elapsedRealtime()<r)SystemClock.sleep(100);
            if(!fixture.drawn||!source())throw new IllegalStateException("Drawn diagram not ready");out.putBoolean("diagram_drawn_without_answer_in_tree",true);
            int[] expected=Arrays.copyOf(fixture.marks,4);ask();
            out.putInt("start_cards",0);out.putInt("other_approvals",0);out.putBoolean("provider_wire_observed",false);
            long deadline=SystemClock.elapsedRealtime()+185000;boolean resultOpened=false,correct=false;
            while(!correct&&SystemClock.elapsedRealtime()<deadline){
                if(Build.VERSION.SDK_INT>=33)ui.clearCache();
                for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo root=w.getRoot();if(root==null||!harness.contentEquals(root.getPackageName()))continue;
                    List<AccessibilityNodeInfo> visible=new ArrayList<>();walk(root,harness,visible);boolean idle=false;
                    for(AccessibilityNodeInfo n:visible){
                        if(label(n,"允许并开始","本次托管并开始","Allow and start","Run hands-free for this task")){out.putInt("start_cards",1);throw new IllegalStateException("Unexpected start card");}
                        if(label(n,"仅允许一次","在此对话中允许","批准并执行","批准此操作？","Allow once","Allow in this chat")){out.putInt("other_approvals",1);throw new IllegalStateException("Unexpected action approval");}
                        if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&label(n,"任务进度：已完成","任务进度：任务已结束 · 请查看结果","任务进度：未完成","任务进度：需要你处理")){
                            ended=true;if(!resultOpened){click(n);resultOpened=true;}break;}
                        idle|=label(n,"在应用中继续","新问题");
                    }
                    if(w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&(idle||ended))correct|=answer(visible,expected);
                }
                if(!correct)SystemClock.sleep(130);
            }
            out.putBoolean("four_current_markers_correct_in_order",correct);if(!correct)throw new IllegalStateException("No current answer identifying all four drawn markers");
            out.putBoolean("passed",true);
        }catch(Exception e){out.putString("failure",e.getMessage());if(ui!=null&&submitted&&!ended&&PKG.equals(foreground()))try{command("input keyevent KEYCODE_ASSIST");}catch(Exception ignored){}}
        finally{if(fixture!=null){StringBuilder truth=new StringBuilder();for(int m:fixture.marks){if(truth.length()>0)truth.append(" → ");truth.append(VisualRouteActivity.COLORS[m/4]).append(VisualRouteActivity.SHAPES[m%4]);}
            out.putString("rendered_fixture_answer",truth.toString());}finish(out.getBoolean("passed")?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
    }
}
