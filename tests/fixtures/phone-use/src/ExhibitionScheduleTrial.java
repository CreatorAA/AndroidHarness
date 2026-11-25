package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Submit one ordinary question using the phone's selected model; do not pick the exhibit,
 * grant a permission, or infer success from the assistant saying it visited the page. */
public final class ExhibitionScheduleTrial extends ReadPageProbe {
    private static final String FIXTURE="com.androidharness.fixture.phoneuse";
    private static final String REQUEST="帮我去这份日程里查一下周日下午那场摄影展几点停止入场";
    private boolean opted,audit,submitted,terminal;
    private final Bundle report=new Bundle();
    @Override public void onCreate(Bundle a){
        opted="one-explicit-device-trial".equals(a.getString("live"));audit="true".equals(a.getString("inspectStructuredAction"));super.onCreate(a);
    }
    private String fg(){
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();AccessibilityNodeInfo n=ui.getRootInActiveWindow();
        return n==null?"":String.valueOf(n.getPackageName());
    }
    private String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private void collect(AccessibilityNodeInfo n,String pkg,List<AccessibilityNodeInfo> out){
        if(n==null||!pkg.contentEquals(n.getPackageName()))return;
        if(n.isVisibleToUser())out.add(n);
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),pkg,out);
    }
    private boolean scheduleVisible(){
        if(!FIXTURE.equals(fg()))return false;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(ui.getRootInActiveWindow(),FIXTURE,nodes);
        int titles=0,stars=0;boolean afternoon=false;
        for(AccessibilityNodeInfo n:nodes){
            if(text(n).equals("河岸来信")&&!n.isClickable()){
                AccessibilityNodeInfo p=n.getParent();int steps=1;
                while(p!=null&&!p.isClickable()&&steps<12){p=p.getParent();steps++;}
                if(p!=null&&p.isClickable()&&steps>=3)titles++;
            }
            if(n.isClickable()&&n.getContentDescription()!=null&&n.getContentDescription().toString().startsWith("收藏："))stars++;
            afternoon|=text(n).contains("周日 14:00");if(text(n).contains("停止入场"))return false;
        }
        return titles==3&&stars==3&&afternoon;
    }
    private void ask() throws Exception{
        command("input keyevent KEYCODE_ASSIST");
        if(waitNode(n->n.isEditable()||label(n,"改用键盘","新问题","问问这个屏幕"),3000)==null){
            if(!FIXTURE.equals(fg()))throw new IllegalStateException("Source lost before assistant opened");
            command("cmd voiceinteraction show");
        }
        AccessibilityNodeInfo f=waitNode(AccessibilityNodeInfo::isEditable,300);
        if(f==null){click(waitNode(n->label(n,"改用键盘","Type instead"),3500));f=waitNode(AccessibilityNodeInfo::isEditable,3500);}
        if(f==null||!f.isShowingHintText()&&!text(f).isEmpty()&&!label(f,"继续追问","问问这个屏幕"))
            throw new IllegalStateException("No empty assistant composer");
        Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,REQUEST);
        if(!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)||waitNode(n->n.isEditable()&&REQUEST.equals(text(n)),1500)==null)
            throw new IllegalStateException("Could not enter question");
        click(waitNode(n->label(n,"发送","Send"),2500));submitted=true;
    }
    private boolean currentAnswer(List<AccessibilityNodeInfo> nodes,String clock){
        int last=-1;for(int i=0;i<nodes.size();i++)if(!nodes.get(i).isEditable()&&REQUEST.equals(text(nodes.get(i))))last=i;
        if(last<0)return false;
        String[] hm=clock.split(":");String hour=hm[0],minute=String.valueOf(Integer.parseInt(hm[1]));
        String half=String.valueOf(Integer.parseInt(hour)-12);
        Pattern time=Pattern.compile("(?<![0-9])(?:"+hour+"|(?:下午)\\s*"+half+")\\s*(?:[:：]\\s*"+hm[1]+"|[时点]\\s*0?"+minute+"(?:分)?)(?![0-9])");
        for(int i=last+1;i<nodes.size();i++){
            AccessibilityNodeInfo n=nodes.get(i);String s=text(n);
            if(n.isEditable()||s.length()>1400||s.indexOf('{')>=0||s.contains("observationId"))continue;
            if(time.matcher(s).find())return true;
        }
        return false;
    }
    @Override public void onStart(){
        ExhibitionScheduleActivity schedule=null;
        try{
            if(!opted||!FIXTURE.equals(target)||!"com.androidharness.app".equals(harness))throw new IllegalArgumentException("Fixture and release assistant opt-in required");
            if(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Device locked");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
            if(audit){new StoppedApprovalInspector(ui,harness,REQUEST,FIXTURE,report).inspectReferenceAction();return;}
            String current=fg();ComponentName home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).resolveActivity(getTargetContext().getPackageManager());
            if(!FIXTURE.equals(current)&&!harness.equals(current)&&!"com.miui.home".equals(current)&&(home==null||!home.getPackageName().equals(current)))
                throw new IllegalStateException("Phone in another app; did not switch away");
            ActivityMonitor monitor=addMonitor(ExhibitionScheduleActivity.class.getName(),null,false);
            try{command("am start -W -f 0x10008000 -n "+FIXTURE+"/.ExhibitionScheduleActivity");
                schedule=(ExhibitionScheduleActivity)waitForMonitorWithTimeout(monitor,4500);}finally{removeMonitor(monitor);}
            if(schedule==null||schedule.cutoffRendered||schedule.targetOpens!=0||schedule.otherOpens!=0||schedule.stars!=0)
                throw new IllegalStateException("Expected fresh program list");
            long ready=SystemClock.elapsedRealtime()+3500;boolean shape;
            do{shape=scheduleVisible();if(!shape)SystemClock.sleep(100);}while(!shape&&SystemClock.elapsedRealtime()<ready);
            if(!shape)throw new IllegalStateException("Expected three homonymous sessions, nested titles, and three independent stars");
            report.putBoolean("three_ambiguous_sessions_and_controls",true);ask();
            report.putInt("start_cards",0);report.putInt("other_approvals",0);
            long until=SystemClock.elapsedRealtime()+210000;boolean openedResult=false,answer=false;
            while(SystemClock.elapsedRealtime()<until){
                if(schedule.otherOpens!=0||schedule.stars!=0)throw new IllegalStateException("Wrong session or favorite was operated");
                if(Build.VERSION.SDK_INT>=33)ui.clearCache();
                for(AccessibilityWindowInfo w:ui.getWindows()){
                    AccessibilityNodeInfo r=w.getRoot();if(r==null||!harness.contentEquals(r.getPackageName()))continue;
                    List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(r,harness,nodes);boolean idle=false;
                    for(AccessibilityNodeInfo n:nodes){
                        if(label(n,"本次托管并开始","允许并开始")){report.putInt("start_cards",1);throw new IllegalStateException("Start card was not approved");}
                        if(label(n,"仅允许一次","在此对话中允许","批准并执行","批准此操作？")){report.putInt("other_approvals",1);throw new IllegalStateException("Action was not approved");}
                        if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&label(n,"任务进度：已完成","任务进度：任务已结束 · 请查看结果","任务进度：未完成","任务进度：需要你处理")){
                            terminal=true;
                            if(!openedResult){click(n);openedResult=true;}break;
                        }
                        idle|=label(n,"在应用中继续","新问题");
                    }
                    if(w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&(idle||terminal)&&schedule.cutoffRendered)
                        answer|=currentAnswer(nodes,schedule.cutoff);
                }
                if(answer)break;SystemClock.sleep(140);
            }
            if(!answer||schedule.targetOpens==0||schedule.otherOpens!=0||schedule.stars!=0||!schedule.cutoffRendered)
                throw new IllegalStateException("No verified current-turn answer with the rendered cutoff");
            report.putString("verified_cutoff",schedule.cutoff);report.putBoolean("current_answer_contains_cutoff",true);report.putBoolean("passed",true);
        }catch(Exception e){report.putString("failure",audit?"Trace reader unavailable":e.getMessage());
            if(!audit&&submitted&&!terminal&&ui!=null&&FIXTURE.equals(fg()))try{command("input keyevent KEYCODE_ASSIST");}catch(Exception ignored){}
        }finally{
            if(schedule!=null){report.putInt("correct_session_opens",schedule.targetOpens);report.putInt("other_session_opens",schedule.otherOpens);
                report.putInt("favorite_taps",schedule.stars);report.putBoolean("cutoff_rendered",schedule.cutoffRendered);}
            finish((audit?report.getBoolean("matched_action_observation"):report.getBoolean("passed"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
        }
    }
}
