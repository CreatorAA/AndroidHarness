package com.androidharness.fixture.phoneuse;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One opt-in real QQ investigation. Never start QQ, export its tree, take a
 * screenshot, capture an answer verbatim, or grant an approval. Only the separate owner-opted card-body check taps QQ directly. */
public final class LiveQqReferenceTrial extends ReadPageProbe {
    private static final String QQ="com.tencent.mobileqq";
    private static final String REQUEST="帮我自动化分析一下这个人在惊讶什么。只查当前群里‘真有啊’这条消息引用的原文和前后文，不发送、不转发，不去其他会话。";
    private static final long MAX_PANEL_GAP_MS=45000;
    private final Bundle report=new Bundle();
    private boolean opted,observeSubmitted,inspectStopped,inspectShape,inspectAction,terminal,opened,returned,answer,qualifies,characterizes,sourceObserved;
    private volatile boolean submitted,clickedCard,clickedBeforeHandoff;
    private String expectedPrefix,groupHeader,detailHeader; // ephemeral window guards, never exported
    private boolean historicalSearch,checkBody;
    private String historicalSuffix;
    private int sourceWindow,sourceTask,assistantWindow=-1,lastVerifiedWindow=-1,lastVerifiedPage;
    private ComponentName sourceActivity,lastVerifiedActivity;
    private String nativeWindow,confirmedVoiceFocus;
    private Rect titleBounds;
    private String titleId;
    private int sourceTerms,answerTerms; // fixed non-identifying technical vocabulary only

    @Override public void onCreate(Bundle args){opted="owner-current-qq-quote".equals(args.getString("live"));expectedPrefix=args.getString("groupPrefix","").trim();checkBody="true".equals(args.getString("checkCardBody"));observeSubmitted="true".equals(args.getString("observeSubmitted"));inspectStopped="true".equals(args.getString("inspectStoppedApproval"));inspectShape="true".equals(args.getString("inspectReferenceShape"));inspectAction="true".equals(args.getString("inspectReferenceAction"))||"true".equals(args.getString("inspectStructuredAction"));historicalSearch="true".equals(args.getString("afterSyntheticRuns"));historicalSuffix=args.getString("expectedResourceSuffix","");super.onCreate(args);}
    private static String txt(AccessibilityNodeInfo n){CharSequence s=n.getText();return s==null?"":s.toString();}
    private static String desc(AccessibilityNodeInfo n){CharSequence s=n.getContentDescription();return s==null?"":s.toString();}
    private static boolean reaction(String s){return s.matches("真有啊[!！。…]*");}
    private static boolean quote(String s){return s.length()>=5 && s.length()<=70 && s.endsWith("的聊天记录");}
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String pkg,Predicate<AccessibilityNodeInfo> fn){
        if(node==null||!pkg.contentEquals(node.getPackageName()))return null;
        if(node.isVisibleToUser()&&fn.test(node))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo r=find(node.getChild(i),pkg,fn);if(r!=null)return r;}
        return null;
    }
    private static Rect bounds(AccessibilityNodeInfo n){Rect r=new Rect();n.getBoundsInScreen(r);return r;}
    private static boolean header(AccessibilityNodeInfo n,Rect window){
        Rect r=bounds(n);return !r.isEmpty()&&r.centerY()<window.top+window.height()/7;
    }
    private static int terms(String s){
        s=s.toLowerCase(Locale.ROOT);
        return (s.contains("huggingface")||s.contains("hugging face")?1:0) |
                (s.contains("safetensors")?2:0)|(s.contains("gguf")?4:0)|(s.contains("onnx")?8:0)|
                (s.contains("权重")?16:0)|(s.contains("仓库")?32:0)|(s.contains("文件")?64:0);
    }
    private AccessibilityNodeInfo active(){if(Build.VERSION.SDK_INT>=33)ui.clearCache();return ui.getRootInActiveWindow();}
    private boolean initial(AccessibilityNodeInfo r){
        if(expectedPrefix.length()<2)return false;
        if(r==null||!QQ.contentEquals(r.getPackageName()))return false;
        Rect window=bounds(r);
        AccessibilityNodeInfo title=find(r,QQ,n->header(n,window)&&txt(n).startsWith(expectedPrefix));
        if(title==null)return false;
        boolean phrase=find(r,QQ,n->reaction(txt(n))||reaction(desc(n)))!=null;
        int[] cards={0};String[] label={null};Rect[] lastBounds={null};
        find(r,QQ,n->{String t=txt(n),d=desc(n);if(!header(n,window)&&(quote(t)||quote(d))){
            String candidate=quote(t)?t:d;Rect area=bounds(n);
            if(!candidate.equals(label[0])||lastBounds[0]==null||!Rect.intersects(area,lastBounds[0])){
                cards[0]++;label[0]=candidate;lastBounds[0]=area;}}
            return false;});
        // Reject multiple reference cards instead of picking the first.
        if(!phrase||cards[0]!=1)return false;
        if(groupHeader!=null&&(!groupHeader.equals(txt(title))||!detailHeader.equals(label[0])))return false;
        groupHeader=txt(title);detailHeader=label[0];titleBounds=bounds(title);titleId=title.getViewIdResourceName();return true;
    }
    private ActivityManager.RunningTaskInfo topQqTask(){
        ui.adoptShellPermissionIdentity("android.permission.REAL_GET_TASKS");
        try{
            List<ActivityManager.RunningTaskInfo> tasks=getContext().getSystemService(ActivityManager.class).getRunningTasks(1);
            if(tasks.size()!=1)return null;
            ActivityManager.RunningTaskInfo t=tasks.get(0);
            return t.topActivity!=null&&QQ.equals(t.topActivity.getPackageName())?t:null;
        }finally{ui.dropShellPermissionIdentity();}
    }
    private static void resourceSuffix(Bundle out,String key,AccessibilityNodeInfo n){
        String id=n.getViewIdResourceName(),prefix=QQ+":id/";
        if(id!=null&&id.startsWith(prefix)&&id.substring(prefix.length()).matches("[A-Za-z][A-Za-z0-9_]{0,31}"))out.putString(key,id.substring(prefix.length()));
    }
    private static final Pattern RISKY_LABEL=Pattern.compile("发送|提交|发布|删除|支付|付款|转账|购买|下单|分享|转发|授权|同意|确认|登录|密码|验证码|权限|\\b(send|submit|share|forward|delete|pay|purchase|confirm|authorize|password)\\b",Pattern.CASE_INSENSITIVE);
    private AccessibilityNodeInfo titleOwner(AccessibilityNodeInfo n){
        for(int i=0;n!=null&&i<36&&QQ.contentEquals(n.getPackageName());i++,n=n.getParent()){
            if(!n.isEnabled()||n.isEditable()||n.isCheckable()||n.isScrollable())return null;
            if(n.isClickable())return n;if(n.isLongClickable())return null;
        }
        return null;
    }
    private void shape(AccessibilityNodeInfo root) throws Exception{
        // initial() has just established the foreground group, reaction and one spatially
        // unique reference. Prefer its deepest labelled node if multiple semantics overlap.
        AccessibilityNodeInfo[] labelled={null};int[] depth={-1};Rect frame=bounds(root);
        find(root,QQ,n->{if(!header(n,frame)&&(detailHeader.equals(txt(n))||detailHeader.equals(desc(n)))){
            int d=0;for(AccessibilityNodeInfo p=n.getParent();p!=null&&d<32;p=p.getParent())d++;
            if(d>depth[0]){depth[0]=d;labelled[0]=n;}
        }return false;});
        if(labelled[0]==null)return;
        AccessibilityNodeInfo leaf=labelled[0];report.putBoolean("title_is_text",detailHeader.equals(txt(leaf)));
        report.putBoolean("title_is_description",detailHeader.equals(desc(leaf)));report.putBoolean("label_clickable",leaf.isClickable());
        report.putBoolean("label_editable",leaf.isEditable());resourceSuffix(report,"label_resource_suffix",leaf);
        AccessibilityNodeInfo control=leaf;int hops=0;
        while(control!=null&&QQ.contentEquals(control.getPackageName())&&!control.isClickable()&&hops<32){control=control.getParent();hops++;}
        boolean found=control!=null&&QQ.contentEquals(control.getPackageName())&&control.isClickable();
        report.putInt("clickable_ancestor_hops",found?hops:-1);
        if(found){
            resourceSuffix(report,"control_resource_suffix",control);
            report.putBoolean("control_editable",control.isEditable());
            boolean local=!control.isScrollable()&&bounds(control).height()<=frame.height()/2;
            report.putBoolean("control_is_local_to_card",local);
            if(local){
                boolean[] other={false},interactive={false},risky={false};
                final AccessibilityNodeInfo button=control;
                ReadTargetEvidence.Node parent=new ReadTargetEvidence.Node(button);
                java.util.List<ReadTargetEvidence.Node> owned=new java.util.ArrayList<>();
                boolean[] web={false};
                find(button,QQ,n->{
                    String t=txt(n),d=desc(n);
                    other[0]|=(!t.isEmpty()&&!detailHeader.equals(t))||(!d.isEmpty()&&!detailHeader.equals(d));
                    interactive[0]|=n.isClickable()&&!n.equals(button)&&!n.equals(leaf);
                    risky[0]|=(n.isClickable()||n.isEditable())&&(RISKY_LABEL.matcher(t).find()||RISKY_LABEL.matcher(d).find());
                    if(!t.trim().isEmpty()||!d.trim().isEmpty()){
                        AccessibilityNodeInfo owner=titleOwner(n);
                        if(button.equals(owner)){
                            owned.add(n.equals(button)?parent:new ReadTargetEvidence.Node(n));
                            web[0]|=String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT).contains("webview");
                        }
                    }
                    return false;
                });
                report.putBoolean("control_only_known_reference_title",!other[0]);
                report.putBoolean("control_has_other_clickable_descendant",interactive[0]);
                report.putBoolean("control_has_sensitive_button_label",risky[0]);
                java.util.Set<AccessibilityNodeInfo> referenceOwners=new java.util.HashSet<>();
                find(root,QQ,n->{if(ReadTargetEvidence.title(txt(n))||ReadTargetEvidence.title(desc(n))){
                    AccessibilityNodeInfo owner=titleOwner(n);if(owner!=null)referenceOwners.add(owner);
                }return false;});
                android.graphics.Point display=new android.graphics.Point();
                getContext().getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealSize(display);
                ReadTargetEvidence.publish(ReadTargetEvidence.classify(parent,owned,display.y),report);
                report.putBoolean("local_visible_evidence_only",true);report.putBoolean("lease_inspection_app_exposed",false);
                report.putInt("visible_tap_owners_with_full_reference_title",referenceOwners.size());
                report.putBoolean("local_parent_or_owned_label_is_webview",web[0]||
                    String.valueOf(button.getClassName()).toLowerCase(Locale.ROOT).contains("webview"));
            }
        }
        report.putString("status","local_reference_shape_classified");
    }
    private static boolean inside(AccessibilityNodeInfo n,AccessibilityNodeInfo ancestor){
        for(int i=0;n!=null&&i<60;i++,n=n.getParent())if(n.equals(ancestor))return true;
        return false;
    }
    private boolean frontUncovered(AccessibilityNodeInfo root, int x,int y){
        List<AccessibilityWindowInfo> ws=ui.getWindows(); AccessibilityWindowInfo app=foremostApp(ws);
        if(app==null||app.getId()!=root.getWindowId()||hasVoicePanelAbove(ws,app))return false;
        for(AccessibilityWindowInfo w:ws)if(w.getLayer()>app.getLayer()){
            Rect box=new Rect();w.getBoundsInScreen(box);if(box.contains(x,y))return false;
        }
        return true;
    }
    // One physical hit-test explicitly requested by the owner. Never select a point from
    // a historical node id. A matching resource suffix alone does not identify a control.
    private void checkCardBody(AccessibilityNodeInfo root) throws Exception{
        Rect viewport=bounds(root); AccessibilityNodeInfo leaf=find(root,QQ,n->!header(n,viewport)&&
                detailHeader.equals(txt(n))&&!n.isClickable()&&!n.isEditable());
        if(leaf==null)return; AccessibilityNodeInfo card=titleOwner(leaf);if(card==null||card.equals(leaf))return;
        Rect body=bounds(card),title=bounds(leaf); if(!viewport.contains(body)||!body.contains(title)||body.height()>viewport.height()/2)return;
        java.util.ArrayList<Rect> passive=new java.util.ArrayList<>(),forbid=new java.util.ArrayList<>();
        boolean[] nested={false};
        find(root,QQ,n->{
            if(n.equals(card)||inside(card,n))return false;
            boolean child=inside(n,card);
            boolean touch=n.isClickable()||n.isLongClickable()||n.isEditable()||n.isCheckable()||
                    n.isScrollable()||RISKY_LABEL.matcher(txt(n)).find()||RISKY_LABEL.matcher(desc(n)).find();
            if(touch){forbid.add(bounds(n));if(child)nested[0]=true;}
            if(child&&!touch&&n.getChildCount()==0&&(!txt(n).isEmpty()||!desc(n).isEmpty()))passive.add(bounds(n));
            return false;
        });
        if(nested[0]||RISKY_LABEL.matcher(txt(card)).find()||RISKY_LABEL.matcher(desc(card)).find())return;
        AccessibilityNodeInfo[] same={null};int[] count={0};
        find(root,QQ,n->{if((QQ+":id/lo3").equals(n.getViewIdResourceName())){count[0]++;same[0]=n;}return false;});
        report.putInt("current_visible_lo3_count",count[0]);report.putBoolean("historical_observation_identity_established",false);
        int[] fingerprints={0};AccessibilityNodeInfo[] matches={null};
        find(root,QQ,n->{if(!(QQ+":id/lo3").equals(n.getViewIdResourceName())||!n.isEnabled()||!n.isClickable()||
                n.isEditable()||n.isCheckable()||n.isScrollable()||!txt(n).isEmpty()||desc(n).trim().isEmpty()||
                quote(desc(n))||RISKY_LABEL.matcher(desc(n)).find()||desc(n).matches("(?is).*(?:https?://|www\\.).*"))return false;
            for(int c=0;c<n.getChildCount();c++)if(find(n.getChild(c),QQ,ch->ch.isClickable()||ch.isEditable()||
                    !txt(ch).trim().isEmpty()||!desc(ch).trim().isEmpty())!=null)return false;
            fingerprints[0]++;matches[0]=n;return false;
        });
        report.putInt("current_lo3_matching_previous_shape",fingerprints[0]);
        if(count[0]==1&&fingerprints[0]==1){
            AccessibilityNodeInfo candidate=matches[0];Rect where=bounds(candidate);
            boolean ancestor=inside(card,candidate),descendant=inside(candidate,card);
            report.putBoolean("current_candidate_intersects_card",Rect.intersects(body,where));
            report.putBoolean("current_candidate_center_inside_card",body.contains(where.centerX(),where.centerY()));
            report.putBoolean("current_candidate_is_card_ancestor",ancestor);
            report.putBoolean("current_candidate_is_card_descendant",descendant);
            report.putBoolean("current_candidate_is_independent_clickable",candidate.isClickable()&&!ancestor&&!descendant);
        }
        // No stored geometry, names or text leave the test.
        int gap=Math.max(3,Math.round(4*getContext().getResources().getDisplayMetrics().density));int x=-1,y=-1;
        for(int phase=0;phase<2&&x<0;phase++) for(int row=1;row<=12&&x<0;row++)for(int col=1;col<=12;col++){
            int cx=body.left+body.width()*col/13,cy=body.top+body.height()*row/13;
            Rect pad=new Rect(cx-gap,cy-gap,cx+gap+1,cy+gap+1);
            if(!body.contains(pad)||Rect.intersects(pad,title))continue;
            boolean excluded=false,hasText=false; for(Rect r:forbid)excluded|=Rect.intersects(r,pad);
            for(Rect r:passive)hasText|=Rect.intersects(r,pad);
            if(excluded||(phase==0&&hasText)||!frontUncovered(root,cx,cy))continue;
            x=cx;y=cy;report.putString("tap_region",hasText?"passive_content":"card_whitespace");break;
        }
        AccessibilityNodeInfo current=active();
        if(x<0||!sameTask()||current==null||current.getWindowId()!=sourceWindow||!initial(current)||!frontUncovered(current,x,y))return;
        if(!card.refresh()||!leaf.refresh()||!body.equals(bounds(card))||!title.equals(bounds(leaf))||!card.equals(titleOwner(leaf)))return;
        command("input tap "+x+" "+y); report.putBoolean("non_title_screen_gesture_sent",true);
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){
            ActivityManager.RunningTaskInfo t=topQqTask();AccessibilityNodeInfo r=active();
            if(t==null||t.taskId!=sourceTask||r==null||!QQ.contentEquals(r.getPackageName()))return;
            if(page(r)==1){SystemClock.sleep(110);continue;}
            Rect frame=bounds(r);
            boolean exact=find(r,QQ,n->header(n,frame)&&detailHeader.equals(txt(n)))!=null;
            if(!exact)return;
            boolean content=find(r,QQ,n->!header(n,frame)&&!n.isEditable()&&!n.isClickable()&&
                    !quote(txt(n))&&txt(n).trim().length()>=3)!=null;
            if(!content){SystemClock.sleep(90);continue;}
            report.putBoolean("exact_quoted_detail_title_matches",true);report.putBoolean("body_present",true);
            if(!frontUncovered(r,frame.centerX(),frame.centerY()))return;
            command("input keyevent KEYCODE_BACK");report.putBoolean("returned_once",true);
            long backDeadline=SystemClock.elapsedRealtime()+3500;
            do{AccessibilityNodeInfo back=active();if(sameTask()&&initial(back)){
                report.putString("status","card_body_opened_and_returned");return;}
                if(back!=null&&!QQ.contentEquals(back.getPackageName()))return;
                SystemClock.sleep(120);
            }while(SystemClock.elapsedRealtime()<backDeadline);
            return;
        }
    }
    private boolean sameTask(){
        ActivityManager.RunningTaskInfo t=topQqTask();
        return t!=null&&t.taskId==sourceTask&&sourceActivity.equals(t.topActivity);
    }
    private boolean lastVerifiedTaskStillOnTop(){
        ActivityManager.RunningTaskInfo t=topQqTask();
        return t!=null&&t.taskId==sourceTask&&t.topActivity.equals(lastVerifiedActivity);
    }
    // Accessibility can omit a completely covered window. WindowManager's token is independent
    // of that occlusion and identifies the individual WindowState, not merely the QQ package.
    // This package-filtered query supplies window metadata, never the chat's contents.
    private static final Pattern QQ_WINDOW=Pattern.compile("^\\s*Window #\\d+ Window\\{([0-9a-fA-F]+) u\\d+ (com\\.tencent\\.mobileqq/[^ }]+)\\}.*$");
    private String visibleNativeWindow() throws Exception{
        String candidate=null,result=null;int matches=0;boolean surface=false,onScreen=false;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand("dumpsys window com.tencent.mobileqq"))))){
            String line;while((line=reader.readLine())!=null){
                if(line.trim().startsWith("Window #")){
                    if(candidate!=null&&surface&&onScreen){matches++;result=candidate;}
                    candidate=null;surface=false;onScreen=false;
                    Matcher m=QQ_WINDOW.matcher(line);
                    if(m.matches()&&sourceActivity.equals(ComponentName.unflattenFromString(m.group(2))))candidate=m.group(1);
                }else if(candidate!=null){surface|=line.contains("mHasSurface=true");onScreen|=line.contains("isOnScreen=true");}
            }
        }
        if(candidate!=null&&surface&&onScreen){matches++;result=candidate;}
        return matches==1?result:null;
    }
    // On some builds even the assistant's accessibility root briefly disappears. Match the
    // exact focused native voice-session window captured with the verified Harness composer.
    private String voiceFocus() throws Exception{
        Pattern voice=Pattern.compile("mCurrentFocus=Window\\{([0-9a-fA-F]+) u\\d+ VoiceInteractionSession\\}");
        String token=null;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand("dumpsys window displays"))))){
            String line;while((line=reader.readLine())!=null){
                Matcher match=voice.matcher(line);if(match.find())token=match.group(1);
            }
        }
        return token;
    }
    // Only inspect the original title slot. The expanded panel may hide the reaction, card, or
    // even all QQ text; their absence is not evidence of a navigation in the underlying window.
    private boolean changedTitle(AccessibilityNodeInfo root){
        return find(root,QQ,n->{
            Rect r=bounds(n);
            if(titleId!=null&&!titleId.equals(n.getViewIdResourceName()))return false;
            if(Math.abs(r.centerY()-titleBounds.centerY())>titleBounds.height()/2||
               !(Math.abs(r.left-titleBounds.left)<titleBounds.height()/2||
                 Math.abs(r.centerX()-titleBounds.centerX())<titleBounds.height()/2))return false;
            String s=txt(n);return !s.isEmpty()&&!groupHeader.equals(s);
        })!=null;
    }
    private boolean handoffMatches() throws Exception{
        if(clickedBeforeHandoff||!sameTask())return false;
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();
        for(AccessibilityWindowInfo w:ui.getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){
            AccessibilityNodeInfo r=w.getRoot();
            if(w.getId()==sourceWindow)return r==null|| (QQ.contentEquals(r.getPackageName())&&!changedTitle(r));
            if(r!=null&&QQ.contentEquals(r.getPackageName()))return false;
        }
        return nativeWindow!=null&&nativeWindow.equals(visibleNativeWindow())&&!clickedBeforeHandoff;
    }
    // getRootInActiveWindow() follows accessibility focus, which can remain on QQ while
    // VoiceInteractionSession is visibly above it. WindowManager orders the windows by layer.
    private AccessibilityWindowInfo foremostApp(List<AccessibilityWindowInfo> windows){
        AccessibilityWindowInfo app=null;
        for(AccessibilityWindowInfo w:windows)if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){
            AccessibilityNodeInfo r=w.getRoot();
            if(r!=null&&harness.contentEquals(r.getPackageName())&&isVoiceWindow(w))continue;
            if(app==null||w.getLayer()>app.getLayer())app=w;
        }
        return app;
    }
    private boolean isVoiceWindow(AccessibilityWindowInfo w){
        if("VoiceInteractionSession".contentEquals(w.getTitle()==null?"":w.getTitle()))return true;
        AccessibilityNodeInfo r=w.getRoot();
        return r!=null&&harness.contentEquals(r.getPackageName())&&
                (w.isFocused()||w.isActive()||find(r,harness,n->n.isEditable()||label(n,"新问题","改用键盘","发送","停止生成"))!=null);
    }
    private static boolean isInputMethod(List<AccessibilityWindowInfo> windows,AccessibilityNodeInfo top){
        for(AccessibilityWindowInfo w:windows)if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD&&w.getId()==top.getWindowId())return true;
        return false;
    }
    private boolean hasVoicePanelAbove(List<AccessibilityWindowInfo> windows,AccessibilityWindowInfo app){
        for(AccessibilityWindowInfo w:windows){
            if(app!=null&&w.getLayer()<=app.getLayer())continue;
            int type=w.getType();
            if(type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY||type==AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            if(isVoiceWindow(w))return true;
        }
        return false;
    }
    // When a full-screen assistant takes window focus, WindowManager may omit the entire QQ
    // window (including its native isOnScreen flag). A missing window is not a different page.
    // Trust only enough to wait: the same top task AND Activity, our own panel, no foreign app.
    // Do not read chat content, dispatch anything, or count a detail visit during this interval.
    private boolean ourPanelIsOnlyCover(List<AccessibilityWindowInfo> windows,AccessibilityNodeInfo top) throws Exception{
        boolean own=top!=null&&harness.contentEquals(top.getPackageName())&&top.getWindowId()==assistantWindow;
        for(AccessibilityWindowInfo w:windows){
            AccessibilityNodeInfo r=w.getRoot();
            int type=w.getType();
            if(type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY||type==AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            if(r!=null&&harness.contentEquals(r.getPackageName())&&(w.getId()==assistantWindow||isVoiceWindow(w))){own=true;continue;}
            if(type==AccessibilityWindowInfo.TYPE_APPLICATION&&w.getId()!=lastVerifiedWindow)return false;
            if(type==AccessibilityWindowInfo.TYPE_APPLICATION&&r!=null&&!QQ.contentEquals(r.getPackageName()))return false;
        }
        return own||(confirmedVoiceFocus!=null&&confirmedVoiceFocus.equals(voiceFocus()));
    }
    /** 1 = original group, 2 = its forward viewer, 0 = not verifiably either. */
    private int page(AccessibilityNodeInfo r){
        Rect window=bounds(r);
        if(find(r,QQ,n->header(n,window)&&groupHeader.equals(txt(n)))!=null)return 1;
        return find(r,QQ,n->header(n,window)&&(detailHeader.equals(txt(n))||"群聊的聊天记录".equals(txt(n))))!=null?2:0;
    }
    private void submit() throws Exception{
        command("input keyevent KEYCODE_ASSIST");
        if(waitNode(n->n.isEditable()||label(n,"改用键盘","新问题"),3500)==null){
            AccessibilityNodeInfo r=active();if(r==null||!QQ.contentEquals(r.getPackageName())||!initial(r))throw new IllegalStateException("lost source before opening assistant");
            command("cmd voiceinteraction show");
        }
        AccessibilityNodeInfo keyboard=waitNode(n->label(n,"改用键盘"),250);
        if(keyboard!=null)click(keyboard);
        AccessibilityNodeInfo field=waitNode(AccessibilityNodeInfo::isEditable,4000);
        if(field==null||(!field.isShowingHintText()&&!txt(field).isEmpty()&&!label(field,"继续追问","问问这个屏幕")))
            throw new IllegalStateException("no empty ordinary Harness composer");
        if(!handoffMatches())throw new IllegalStateException("QQ window, task, or group changed before handoff");
        Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,REQUEST);
        if(!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)||waitNode(n->n.isEditable()&&label(n,REQUEST),1400)==null)throw new IllegalStateException("request not entered");
        AccessibilityNodeInfo send=waitNode(n->label(n,"发送"),1800);
        assistantWindow=field.getWindowId();confirmedVoiceFocus=voiceFocus();
        if(!handoffMatches()){
            AccessibilityNodeInfo ownDraft=waitNode(n->n.isEditable()&&label(n,REQUEST),150);
            if(ownDraft!=null){Bundle empty=new Bundle();empty.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"");
                ownDraft.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,empty);}
            throw new IllegalStateException("QQ window, task, or group changed before send");
        }
        report.putBoolean("same_qq_window_and_task_at_send",true);
        click(send);submitted=true;
    }
    private void inspectAnswer(AccessibilityNodeInfo r,boolean currentOnly){
        boolean[] after={currentOnly},saw={false},limit={false},explain={false};int[] tags={0};
        find(r,harness,n->{String s=txt(n);if(n.isEditable()||s.isEmpty())return false;
            if(REQUEST.equals(s)){after[0]=true;return false;}
            if(!after[0]||s.length()<9||s.length()>2900||s.startsWith("{")||s.contains("observationId"))return false;
            boolean describes=s.contains("惊讶")||s.contains("惊诧")||s.contains("意外")||s.contains("没想到")||s.contains("真有啊");
            boolean limited=s.contains("无法确认")||s.contains("不能确定")||s.contains("不足以")||s.contains("没有读到")||s.contains("看不到")||s.contains("无法核实")||s.contains("无法确定")||s.contains("只能");
            if(describes||limited){saw[0]=true;limit[0]|=limited;explain[0]|=describes;tags[0]|=terms(s);}
            return false;});
        if(saw[0]){answer=true;characterizes|=explain[0];qualifies|=limit[0];answerTerms|=tags[0];}
    }
    private boolean approvalControl(AccessibilityNodeInfo n,String... texts){
        if(!label(n,texts))return false;
        for(int i=0;n!=null&&i<4&&harness.contentEquals(n.getPackageName());i++,n=n.getParent()){
            if(n.isVisibleToUser()&&n.isClickable())return true;
        }
        return false;
    }
    private static String previewType(AccessibilityNodeInfo n){
        String text=txt(n);
        if(text.startsWith("允许这一次提交吗？")||text.startsWith("Allow this submission once?"))return "submit";
        if(text.startsWith("允许执行这个动作一次吗？")||text.startsWith("Allow this action once?"))return "ordinary_action_unspecified_risk";
        if(text.startsWith("允许将本次任务切换到另一个应用吗？")||text.startsWith("Switch this task to another app?"))return "switch_application";
        if(text.startsWith("允许此聊天控制手机吗？")||text.startsWith("Allow phone control for this chat?"))return "chat_phone_control";
        return null;
    }
    /** Inspect this already-visible Harness card once. No expansion, screenshots or arguments;
     * its visible fixed consent template identifies the category, never the policy's reason. */
    private void stopAtVisibleApproval(AccessibilityNodeInfo root){
        AccessibilityNodeInfo start=find(root,harness,n->approvalControl(n,"本次托管并开始","允许并开始","Run hands-free for this task","Allow and start"));
        if(start!=null){
            report.putInt("start_cards",1);report.putString("visible_approval_origin","task_start");
            report.putString("visible_approval_category","task_start");report.putBoolean("visible_approval_allow_once",false);
            throw new IllegalStateException("unexpected start card; not approved");
        }
        AccessibilityNodeInfo button=find(root,harness,n->approvalControl(n,"仅允许一次","在此对话中允许","批准并执行","Allow once","Allow in this chat","Approve & run"));
        if(button==null)return;
        boolean once=label(button,"仅允许一次","Allow once");
        report.putInt("other_approvals",1);report.putString("visible_approval_origin","action_approval");
        report.putBoolean("visible_approval_allow_once",once);
        final int bottom=bounds(button).centerY();
        final int[] top={Integer.MIN_VALUE};
        find(root,harness,n->{if(label(n,"允许此更改操作你的手机？","允许代理控制你的手机吗？","批准此操作？",
                "Allow this change to your phone?","Let the agent control your phone?","Approve this action?")){
            int y=bounds(n).centerY();if(y<=bottom&&y>top[0])top[0]=y;
        }return false;});
        String[] category={null};boolean[] ambiguous={false};
        // If the title was clipped, don't attribute an older transcript sentence to this
        // approval. Missing copy is distinct from the visible generic one-action preview.
        if(top[0]!=Integer.MIN_VALUE)find(root,harness,n->{
            int y=bounds(n).centerY();if(y>top[0]&&y<bottom&&!n.isEditable()){
                String c=previewType(n);if(c!=null){if(category[0]!=null&&!c.equals(category[0]))ambiguous[0]=true;category[0]=c;}
            }return false;});
        report.putBoolean("approval_title_visible",top[0]!=Integer.MIN_VALUE);
        report.putBoolean("approval_preview_template_verified",category[0]!=null&&!ambiguous[0]);
        report.putString("visible_approval_category",category[0]!=null&&!ambiguous[0]?category[0]:"risk_not_specified_or_preview_not_visible");
        throw new IllegalStateException("unexpected action approval; not granted");
    }
    /** Never touch another application's controls when stopping; the existing assistant owns its island. */
    private boolean stopTask(){
        if(!submitted||terminal)return true;
        for(int pass=0;pass<3;pass++){
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            for(AccessibilityWindowInfo w:ui.getWindows()){
                if(w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
                AccessibilityNodeInfo r=w.getRoot(),stop=find(r,harness,n->label(n,"停止任务"));
                if(stop!=null){click(stop);return true;}
                if(pass==0){AccessibilityNodeInfo grip=find(r,harness,n->desc(n).startsWith("任务进度："));if(grip!=null)click(grip);}
            }
            SystemClock.sleep(120);
        }
        // The default assistant's documented takeover path cancels its own phone lease.
        if(observeSubmitted)return false; // An old cancelled run need not own a lease anymore.
        try{command("input keyevent KEYCODE_ASSIST");return true;}catch(Exception ignored){return false;}
    }
    @Override public void onStart(){
        try{
            if(!opted||!QQ.equals(target)||!"com.androidharness.app".equals(harness))throw new IllegalArgumentException("explicit owner opt-in, target QQ, release Harness required");
            if(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("phone locked");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo i=ui.getServiceInfo();i.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(i);
            if(historicalSearch&&!inspectAction)throw new IllegalArgumentException("Historical lookup requires the read-only audit");
            if(inspectAction){
                StoppedApprovalInspector audit=new StoppedApprovalInspector(ui,harness,REQUEST,report);
                if(historicalSearch)audit.afterSyntheticRuns(historicalSuffix);
                audit.inspectReferenceAction();
                return;
            }
            if(inspectStopped){
                new StoppedApprovalInspector(ui,harness,REQUEST,report).inspect();
                return;
            }
            AccessibilityNodeInfo source=active();ActivityManager.RunningTaskInfo task=topQqTask();
            if(checkBody){if(task==null||!initial(source)){report.putString("status","skipped_source_not_verified");return;}sourceTask=task.taskId;sourceActivity=task.topActivity;sourceWindow=source.getWindowId();report.putBoolean("source_group_and_quote_verified",true);checkCardBody(source);return;}
            if(inspectShape){
                if(task==null||!initial(source)){report.putString("status","skipped_source_not_verified");return;}
                report.putBoolean("source_group_and_quote_verified",true);
                shape(source);return;
            }
            if(observeSubmitted){
                for(AccessibilityWindowInfo w:ui.getWindows())if(find(w.getRoot(),harness,n->!n.isEditable()&&REQUEST.equals(txt(n)))!=null){
                    assistantWindow=w.getId();break;
                }
                if(assistantWindow<0){report.putString("status","skipped_no_visible_sent_request");return;}
                confirmedVoiceFocus=voiceFocus();
                // A previous process may have already sent this request. If its panel and the
                // source are not simultaneously visible, hold the observed bubble in memory and
                // wait passively for the original group. No simulated tap or send on this path.
                if(task!=null&&(source==null||harness.contentEquals(source.getPackageName()))){
                    long until=SystemClock.elapsedRealtime()+MAX_PANEL_GAP_MS;
                    do{
                        List<AccessibilityWindowInfo> windows=ui.getWindows();
                        AccessibilityWindowInfo app=foremostApp(windows);
                        source=app==null?null:app.getRoot();
                        if(source!=null)break;
                        ActivityManager.RunningTaskInfo current=topQqTask();
                        if(current==null||current.taskId!=task.taskId||!task.topActivity.equals(current.topActivity)||
                                !ourPanelIsOnlyCover(windows,active()))break;
                        SystemClock.sleep(200);
                    }while(SystemClock.elapsedRealtime()<until);
                }
            }
            if(!initial(source)||task==null){report.putString("status","skipped_source_not_verified");return;}
            sourceWindow=source.getWindowId();sourceTask=task.taskId;sourceActivity=task.topActivity;
            lastVerifiedWindow=sourceWindow;lastVerifiedActivity=sourceActivity;lastVerifiedPage=1;
            nativeWindow=visibleNativeWindow();
            report.putBoolean("source_group_and_quote_verified",true);
            ui.setOnAccessibilityEventListener(e->{if(e.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED&&QQ.equals(String.valueOf(e.getPackageName()))){
                if(!submitted){clickedBeforeHandoff=true;return;}
                if(e.getWindowId()==sourceWindow){
                    AccessibilityNodeInfo n=e.getSource();if(find(n,QQ,v->detailHeader.equals(txt(v))||detailHeader.equals(desc(v)))!=null)clickedCard=true;
                }
            }});
            if(observeSubmitted){
                submitted=true;report.putBoolean("observed_previous_request_without_resending",true);
            }else submit();
            report.putInt("start_cards",0);report.putInt("other_approvals",0);
            long deadline=SystemClock.elapsedRealtime()+200000,gapSince=0;boolean usedTerminal=false;
            while(SystemClock.elapsedRealtime()<deadline){
                AccessibilityNodeInfo top=active();
                List<AccessibilityWindowInfo> windows=ui.getWindows();
                AccessibilityWindowInfo app=foremostApp(windows);
                boolean covered=hasVoicePanelAbove(windows,app);
                AccessibilityNodeInfo appRoot=app==null?null:app.getRoot();
                if(top!=null&&!QQ.contentEquals(top.getPackageName())&&!harness.contentEquals(top.getPackageName())&&
                        !(covered&&isInputMethod(windows,top)))throw new IllegalStateException("unexpected foreground; no further investigation");
                for(AccessibilityWindowInfo w:windows){AccessibilityNodeInfo r=w.getRoot();
                    if(r!=null&&harness.contentEquals(r.getPackageName()))stopAtVisibleApproval(r);
                }
                if(appRoot!=null&&!QQ.contentEquals(appRoot.getPackageName()))throw new IllegalStateException("non-QQ foreground application");
                boolean observedPage=false;
                if(appRoot==null){
                    if(!ourPanelIsOnlyCover(windows,top))throw new IllegalStateException("missing QQ window without verified Harness panel");
                    if(!lastVerifiedTaskStillOnTop())throw new IllegalStateException("QQ task or Activity changed while hidden");
                }
                for(AccessibilityWindowInfo w:windows){
                    AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
                    if(QQ.contentEquals(r.getPackageName())){
                        // Other QQ windows may be obscured old activities or popups. Never read
                        // their trees or treat missing headers in those trees as navigation.
                        if(app==null||w.getId()!=app.getId())continue;
                        int p=page(r);
                        if(p==0){
                            if(ourPanelIsOnlyCover(windows,top)&&w.getId()==lastVerifiedWindow&&!changedTitle(r)&&lastVerifiedTaskStillOnTop())continue;
                            throw new IllegalStateException(covered?"covered QQ window or title changed":"foreground QQ header not verified");
                        }
                        observedPage=true;
                        if(w.getId()!=lastVerifiedWindow||p!=lastVerifiedPage){
                            ActivityManager.RunningTaskInfo verified=topQqTask();
                            if(verified==null||verified.taskId!=sourceTask)throw new IllegalStateException("recognized title appeared in a different task");
                            lastVerifiedWindow=w.getId();lastVerifiedActivity=verified.topActivity;lastVerifiedPage=p;
                        }
                        if(p==2){
                            opened=true; Rect viewport=bounds(r);
                            find(r,QQ,n->{if(!header(n,viewport)&&!n.isEditable()){
                                String t=txt(n);sourceTerms|=terms(t);
                                if(t.length()>=10&&!quote(t))sourceObserved=true;
                            }return false;});
                        }else if(p==1&&opened)returned=true;
                        continue;
                    }
                    if(!harness.contentEquals(r.getPackageName()))continue;
                    if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){
                        AccessibilityNodeInfo end=find(r,harness,n->label(n,"任务进度：已完成","任务进度：任务已结束 · 请查看结果","任务进度：未完成","任务进度：需要你处理"));
                        if(end!=null){terminal=true;if(!usedTerminal){click(end);usedTerminal=true;}}
                    }else{
                        // Prefer current-turn anchor; a task-specific result can also be shown in its
                        // own history. Never inspect any earlier unanchored conversation text.
                        inspectAnswer(r,false);
                        if(terminal && usedTerminal && !answer && find(r,harness,n->label(n,REQUEST))==null){
                            // No current-turn anchor in view: do not misattribute an older answer.
                            report.putBoolean("result_anchor_out_of_view",true);
                        }
                    }
                }
                if(!observedPage){
                    long now=SystemClock.elapsedRealtime();
                    if(gapSince==0){gapSince=now;report.putBoolean("waited_under_voice_panel",true);}
                    if(now-gapSince>=MAX_PANEL_GAP_MS)throw new IllegalStateException("no verified QQ page reappeared within panel grace period");
                }else if(gapSince!=0){report.putBoolean("qq_reverified_after_panel",true);gapSince=0;}
                if(terminal&&answer)break;
                SystemClock.sleep(135);
            }
            report.putString("status",opened&&answer&&terminal?"investigated":"incomplete");
        }catch(Exception failure){report.putString("status","stopped");report.putString("reason",failure.getMessage());}
        finally{
            if(checkBody){report.remove("reason");finish("card_body_opened_and_returned".equals(report.getString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);return;}
            if(inspectAction){
                report.remove("reason");
                finish(report.getBoolean("matched_action_observation")?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);return;
            }
            if(inspectShape&&!inspectStopped){
                report.remove("reason");
                finish("local_reference_shape_classified".equals(report.getString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
                return;
            }
            if(inspectStopped){
                // No original text, parsing exception, or other app state is exported.
                report.remove("reason");
                finish(report.getBoolean("stopped_input_classified")?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
                return;
            }
            if(ui!=null)ui.setOnAccessibilityEventListener(null);
            if(submitted&&!terminal)report.putBoolean("takeover_requested",stopTask());
            report.putBoolean("quote_detail_opened",opened);report.putBoolean("quote_click_event",clickedCard);report.putBoolean("detail_contains_body_text",sourceObserved);
            report.putBoolean("returned_to_original_group",returned);report.putBoolean("terminal",terminal);
            report.putBoolean("current_reply_observed",answer);report.putBoolean("reply_explains_reaction",characterizes);report.putBoolean("reply_reports_limits",qualifies);
            // Contact, filename and chat contents never cross ADB. This is corroboration of domain
            // terms, not independent proof that a person's internal motive has been established.
            report.putInt("specific_terms_in_both_quote_and_reply",Integer.bitCount((sourceTerms&answerTerms)&31));
            finish("investigated".equals(report.getString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
        }
    }
}
