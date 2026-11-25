package com.androidharness.fixture.phoneuse;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.function.Predicate;

/** Real-model, read-only investigation. The trial drives only the ordinary assistant composer and
 * may open its terminal result. The outer page does not contain the per-run evidence. */
public final class ReferencedChatTrial extends ReadPageProbe {
    private static final String FIXTURE="com.androidharness.fixture.phoneuse";
    private static final String REQUEST="帮我自动化分析一下这个人在惊讶什么";
    private boolean optIn, submitted, terminal;
    private final Bundle report=new Bundle();

    @Override public void onCreate(Bundle args) {
        optIn="one-explicit-device-trial".equals(args.getString("live")); super.onCreate(args);
    }
    private String foreground() {
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();
        AccessibilityNodeInfo n=ui.getRootInActiveWindow();
        return n==null?"":String.valueOf(n.getPackageName());
    }
    private AccessibilityNodeInfo scan(AccessibilityNodeInfo n, Predicate<AccessibilityNodeInfo> check) {
        if(n==null || !harness.contentEquals(n.getPackageName()))return null;
        if(n.isVisibleToUser() && check.test(n))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=scan(n.getChild(i),check);if(hit!=null)return hit;}
        return null;
    }
    private void mark(String state) {report.putString("stage",state);sendStatus(0,new Bundle(report));}
    private AccessibilityNodeInfo fixtureNode(AccessibilityNodeInfo n, Predicate<AccessibilityNodeInfo> test){
        if(n==null||!FIXTURE.contentEquals(n.getPackageName()))return null;
        if(n.isVisibleToUser()&&test.test(n))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=fixtureNode(n.getChild(i),test);if(found!=null)return found;}
        return null;
    }
    private boolean sourceHasNestedCard(){
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();
        AccessibilityNodeInfo root=ui.getRootInActiveWindow();
        AccessibilityNodeInfo title=fixtureNode(root,n->"群聊的聊天记录".contentEquals(n.getText()==null?"":n.getText()));
        if(title==null||title.isClickable()||title.isEditable())return false;
        AccessibilityNodeInfo card=title.getParent(),other=fixtureNode(root,n->label(n,"谷雨的头像")&&n.isClickable());
        return card!=null&&card.isClickable()&&card.getContentDescription()==null&&card.getText()==null&&other!=null&&!other.equals(card)&&
                fixtureNode(card,n->n.isClickable()&&!n.equals(card))==null&&
                fixtureNode(card,n->n.getText()!=null&&n.getText().toString().contains("我回去又翻了一遍备份"))!=null;
    }
    private void openPanel() throws Exception {
        if(!FIXTURE.equals(foreground()))throw new IllegalStateException("Synthetic source no longer active");
        command("input keyevent KEYCODE_ASSIST");
        if(!ownsRoot() && waitNode(n->n.isEditable() || label(n,"改用键盘","手机助手","新问题"),3000)==null){
            if(!FIXTURE.equals(foreground()))throw new IllegalStateException("Not at synthetic source");
            command("cmd voiceinteraction show");
        }
    }
    private void sendQuestion() throws Exception {
        AccessibilityNodeInfo input=waitNode(AccessibilityNodeInfo::isEditable,250);
        if(input==null){click(waitNode(n->label(n,"改用键盘","Type instead"),3500));input=waitNode(AccessibilityNodeInfo::isEditable,3500);}
        if(input==null)throw new IllegalStateException("No ordinary assistant composer");
        if(!input.isShowingHintText() && input.getText()!=null && input.getText().length()>0
                && !label(input,"继续追问","问问这个屏幕"))
            throw new IllegalStateException("Existing assistant draft left untouched");
        Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,REQUEST);
        if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text) ||
                waitNode(n->n.isEditable() && label(n,REQUEST),2000)==null)
            throw new IllegalStateException("Composer did not accept the original question");
        click(waitNode(n->label(n,"发送","Send"),3000));submitted=true;
    }
    private boolean replyContainsFact(AccessibilityNodeInfo root, String model) {
        // Inspect only visible assistant nodes, never output free-form text. The nonce prevents
        // an earlier chat answer or pre-open guess from satisfying the assertion.
        boolean hasModel=scan(root,n->!n.isEditable() && compact(n).contains(model.replace("-","")))!=null;
        boolean hasFont=scan(root,n->{String s=compact(n);return !n.isEditable() &&
            (s.contains("字库") || s.contains("字体") || s.contains("FONT"));})!=null;
        report.putBoolean("assistant_named_hidden_model",hasModel);
        report.putBoolean("assistant_identified_font",hasFont);
        return hasModel && hasFont;
    }
    private static String compact(AccessibilityNodeInfo n) {
        return n.getText()==null?"":n.getText().toString().toUpperCase(java.util.Locale.ROOT)
            .replace("-","").replace("－","").replace("‑","").replace("–","").replace(" ","");
    }
    @Override public void onStart() {
        ReferencedChatActivity chat=null;
        try {
            if(!optIn || !FIXTURE.equals(target) || !"com.androidharness.app".equals(harness))
                throw new IllegalArgumentException("Requires opt-in, synthetic target and release assistant");
            if(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked())
                throw new IllegalStateException("Unlock the device first");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info=ui.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
            String current=foreground();
            android.content.Intent home=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.ComponentName launcher=home.resolveActivity(getTargetContext().getPackageManager());
            if(!(FIXTURE.equals(current)||harness.equals(current)||"com.miui.home".equals(current)||(launcher!=null&&launcher.getPackageName().equals(current))))
                throw new IllegalStateException("Device is in another app; trial did not switch away");
            ActivityMonitor monitor=addMonitor(ReferencedChatActivity.class.getName(),null,false);
            try {
                command("am start -W -f 0x10008000 -n "+FIXTURE+"/.ReferencedChatActivity");
                chat=(ReferencedChatActivity)waitForMonitorWithTimeout(monitor,4000);
            } finally {removeMonitor(monitor);}
            if(chat==null || chat.opens!=0 || chat.evidenceShown || chat.sends!=0 || chat.neighborTaps!=0)
                throw new IllegalStateException("Expected a fresh outer chat page");
            long ready=SystemClock.elapsedRealtime()+3000;boolean shape;
            do{shape=sourceHasNestedCard();if(!shape)SystemClock.sleep(100);}while(!shape && SystemClock.elapsedRealtime()<ready);
            if(!shape)throw new IllegalStateException("Expected an unnamed clickable parent with title, summary and separate avatar");
            report.putBoolean("nested_card_and_neighbor_verified",true);
            openPanel();sendQuestion();
            report.putInt("start_cards",0);report.putInt("other_approvals",0);mark("original question sent");
            long end=SystemClock.elapsedRealtime()+210000;
            boolean openedReported=false, needResult=true, answered=false;
            while(SystemClock.elapsedRealtime()<end){
                if(chat.sends!=0)throw new IllegalStateException("Fixture send button operated");
                if(chat.neighborTaps!=0)throw new IllegalStateException("Assistant tapped the neighboring avatar, not the quote card");
                if(chat.opens>0 && chat.evidenceShown && !openedReported){
                    openedReported=true;mark("assistant opened quote; hidden evidence rendered");
                }
                if(Build.VERSION.SDK_INT>=33)ui.clearCache();
                for(AccessibilityWindowInfo w:ui.getWindows()){
                    AccessibilityNodeInfo r=w.getRoot();if(r==null||!harness.contentEquals(r.getPackageName()))continue;
                    boolean card=scan(r,n->label(n,"本次托管并开始","允许并开始"))!=null;
                    boolean approval=scan(r,n->label(n,"仅允许一次","在此对话中允许","批准并执行","批准此操作？"))!=null;
                    if(card||approval){report.putInt(card?"start_cards":"other_approvals",1);
                        throw new IllegalStateException("Unexpected confirmation in read-only delegated analysis; not approved");}
                    if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){
                        AccessibilityNodeInfo done=scan(r,n->label(n,"任务进度：已完成","任务进度：任务已结束 · 请查看结果",
                                "任务进度：未完成","任务进度：需要你处理","任务已结束 · 请查看结果","已完成"));
                        if(done!=null){
                            terminal=true;
                            if(!openedReported)throw new IllegalStateException("Task ended without inspecting quoted evidence");
                            if(needResult){click(done);needResult=false;mark("opened completed assistant result");}
                        }
                    }else if(openedReported && replyContainsFact(r,chat.deviceModel)) {
                        // A terminal task result OR ordinary idle reply; never expand a tool's details.
                        answered=terminal || scan(r,n->label(n,"在应用中继续","新问题"))!=null;
                    }
                }
                if(answered)break;
                SystemClock.sleep(130);
            }
            if(!answered || !openedReported || chat.sends!=0 || chat.neighborTaps!=0)
                throw new IllegalStateException("No verified answer identifying the model-specific font from the opened quote");
            report.putString("verified_answer","Reply identifies "+chat.deviceModel+" and the recovered font.");
            report.putBoolean("passed",true);
        } catch(Exception e){
            report.putString("failure",e.toString());
            if(submitted && !terminal && ui!=null && FIXTURE.equals(foreground())){
                try{command("input keyevent KEYCODE_ASSIST");}catch(Exception ignored){}
            }
        } finally {
            if(chat!=null){report.putInt("quote_opens",chat.opens);report.putBoolean("evidence_rendered",chat.evidenceShown);
                report.putInt("fixture_sends",chat.sends);report.putInt("neighbor_avatar_taps",chat.neighborTaps);}
            finish(report.getBoolean("passed")?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
        }
    }
}
