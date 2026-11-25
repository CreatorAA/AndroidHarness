package com.androidharness.fixture.phoneuse;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Explicit live, non-default QA. Operates only the assistant panel. Never touches the launcher
 * or Calculator UI; checks package transitions and a small fixed vocabulary of arithmetic evidence. */
public final class CalculatorJourneyProbe extends ReadPageProbe {
    static final String FIXTURE="com.androidharness.fixture.phoneuse", CALC="com.miui.calculator", HOME="com.miui.home";
    static final String TASK="回到桌面找到计算器，点开它，然后在计算器里算 37×19";
    static final String DRAFT="听写测试草稿", RESET="重开新会话";
    String scenario; boolean opt, submitted, completed;
    volatile boolean watching,homeEvent,calcEvent,windowResult;
    volatile long homeTime,calcTime;
    final Set<String> stages=new LinkedHashSet<>();
    final StringBuffer buttonEvents=new StringBuffer();
    final Bundle out=new Bundle();

    @Override public void onCreate(Bundle args) {
        opt="one-explicit-device-trial".equals(args.getString("live"));
        scenario=args.getString("scenario","");
        super.onCreate(args);
    }
    @Override public void onStart() {
        try {
            if(!opt||!FIXTURE.equals(target)||!"com.androidharness.app".equals(harness))
                throw new IllegalArgumentException("Live opt-in, synthetic source and release assistant required");
            if(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked()) throw new IllegalStateException("Phone locked");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo si=ui.getServiceInfo();
            si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(si);
            if("voice".equals(scenario)) voice();
            else if("journey".equals(scenario)) journey();
            else if("history".equals(scenario)) history();
            else if("inspect".equals(scenario)) inspect();
            else if("island".equals(scenario)) island();
            else if("autojs-roundtrip".equals(scenario)) new AutoJsRoundTripTrial(this).run();
            else if("review-last-qq-action".equals(scenario)) new RetrospectiveTrial(this).run();
            else if("clarify-calculator".equals(scenario)) clarifyCalculator();
            else if("calculator-once".equals(scenario)) new SingleGrantCalculatorTrial(this).run();
            else if("calculator-controls".equals(scenario)) new SingleGrantCalculatorTrial(this).inspectControls();
            else if("reconnect-setup".equals(scenario)) reconnectSetup();
            else if("offline-mic".equals(scenario)) offlineMic();
            else throw new IllegalArgumentException("Unknown physical acceptance scenario");
        } catch(Exception e) {
            out.putString("failure",e.toString());
            if(submitted&&!completed)try {
                // Re-summons is user takeover. Never click a device dialog or consent to another task.
                AccessibilityNodeInfo stop=waitNode(n->label(n,"停止","Stop"),450);
                if(stop!=null) click(stop);
                else if("autojs-roundtrip".equals(scenario)) {
                    if(active(FIXTURE)||ownsRoot())command("input keyevent KEYCODE_ASSIST");
                } else if(!"calculator-once".equals(scenario)||active(HOME)||active(CALC)||ownsRoot())command("input keyevent KEYCODE_ASSIST");
            }catch(Exception ignored){}
        } finally {
            watching=false;
            if(ui!=null)ui.setOnAccessibilityEventListener(null);
            out.putString("calculator_observed",stages.toString());
            out.putString("calculator_numeric_click_events",buttonEvents.toString());
            out.putBoolean("home_window_after_managed",homeEvent);out.putBoolean("calculator_window_after_home",calcEvent);
            out.putBoolean("calculator_displayed_703",windowResult);
            if(homeTime>0&&calcTime>=homeTime)out.putLong("home_to_calculator_ms",calcTime-homeTime);
            finish(out.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
        }
    }
    void phase(String name) {out.putString("stage",name);out.putLong("stage_uptime_ms",SystemClock.elapsedRealtime());sendStatus(0,new Bundle(out));}
    private static final String CLARIFY = "打开计算器，然后在里面算一个乘法：第一个数是9，第二个数好像是19。请先问我第二个数是不是19，等我回答了再在计算器里实际按出算式并核对结果。";
    /** Only the assistant is clicked. The two known display fields and a fixed key vocabulary are read on Calculator. */
    void clarifyCalculator() throws Exception {
        openFixture(); openPanel();
        // Start a fresh quick-panel conversation; preserve all old chats in history.
        enter(RESET); click(button("发送",2500)); SystemClock.sleep(450);
        phase("ordinary prompt from synthetic source");
        enter(CLARIFY); click(button("发送",3200));
        if(button(FIXTURE,6000)==null)throw new IllegalStateException("Expected review of synthetic source");
        AccessibilityNodeInfo start=button("本次托管并开始",3500);
        if(start==null)throw new IllegalStateException("Managed task entry was not shown");
        ui.setOnAccessibilityEventListener(e->{
            if(!watching||!CALC.contentEquals(String.valueOf(e.getPackageName())))return;
            if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)calcEvent=true;
            if(!clarAnswered||e.getEventType()!=AccessibilityEvent.TYPE_VIEW_CLICKED)return;
            AccessibilityNodeInfo source=e.getSource();
            if(source!=null)for(String k:new String[]{"9","1","×","乘","乘以","=","等于"})
                if(label(source,k)){buttonEvents.append(k);break;}
        });
        watching=true; submitted=true;click(start); out.putInt("managed_starts",1);
        long until=SystemClock.elapsedRealtime()+210000;
        boolean expr=false,value=false,terminal=false;
        while(SystemClock.elapsedRealtime()<until){
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            for(AccessibilityWindowInfo w:ui.getWindows()){
                AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
                if(CALC.contentEquals(root.getPackageName())){
                    calcEvent=true;
                    if(clarAnswered){
                        for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByViewId(CALC+":id/expression"))
                            expr|=String.valueOf(n.getText()).replace(" ","").matches("9[×xX*]19");
                        for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByViewId(CALC+":id/result"))
                            value|=String.valueOf(n.getText()).replace(" ","").matches("=?171");
                    }
                }
                if(!harness.contentEquals(root.getPackageName()))continue;
                if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){
                    terminal|=clarAnswered&&matches(root,n->label(n,"已完成","任务进度：已完成","任务已结束 · 请查看结果","任务进度：任务已结束 · 请查看结果"));
                    continue;
                }
                if(!clarAnswered&&matches(root,n->{
                    String t=String.valueOf(n.getText());
                    return !n.isEditable()&&!t.contains(CLARIFY)&&t.length()<360&&
                        (t.contains("？")||t.contains("?")||t.contains("请确认")||t.contains("是不是"))&&
                        (t.contains("19")||t.contains("十九"))&&!t.matches(".*(?:付款|支付|授权|权限).*");
                })){
                    enterComposerReply("对");
                    clarAnswered=true;
                    out.putBoolean("answered_original_question_once",true);
                    phase("answered once in normal composer");
                } else if(clarAnswered && matches(root,n->n.isEnabled()&&label(n,"本次托管并开始","仅允许一次","允许并开始"))){
                    throw new IllegalStateException("Second device authorization requested");
                }
            }
            if(expr&&value&&buttonEvents.toString().matches(".*(?:等于|=).*")&&terminal)break;
            SystemClock.sleep(120);
        }
        out.putBoolean("calculator_expression_9x19",expr);out.putBoolean("calculator_verified_171",value);
        out.putBoolean("terminal_island_observed",terminal);
        if(!clarAnswered||!expr||!value||!buttonEvents.toString().matches(".*(?:等于|=).*"))
            throw new IllegalStateException("No independently verified calculator input and result after answer");
        completed=true;
    }
    volatile boolean clarAnswered;
    /** Only opens the OS settings page through the app's ordinary recovery prompt. Never selects a role. */
    void reconnectSetup() throws Exception {
        command("am start -W -n com.androidharness.app/.MainActivity");
        AccessibilityNodeInfo reconnect=waitNode(n->label(n,"重新连接","Reconnect")&&n.isVisibleToUser(),8500);
        if(reconnect==null)throw new IllegalStateException("No recovery action visible after ordinary launch.");
        out.putBoolean("reconnect_visible",true);
        click(reconnect);
        long until=SystemClock.elapsedRealtime()+4500;
        do {
            if(active("com.android.settings")){
                boolean correct=false;
                try(java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")), "UTF-8"))){
                    String line;while((line=br.readLine())!=null)if(line.contains("com.android.settings/")&&line.contains("ManageAssistActivity"))correct=true;
                }
                if(correct){out.putBoolean("system_assistant_chooser_open",true);completed=true;return;}
            }
            SystemClock.sleep(150);
        }while(SystemClock.elapsedRealtime()<until);
        throw new IllegalStateException("Recovery action did not open the expected system assistant settings.");
    }
    void enterComposerReply(String reply) throws Exception {
        AccessibilityNodeInfo keyboard=button("改用键盘",180); if(keyboard!=null)click(keyboard);
        long until=SystemClock.elapsedRealtime()+4500;
        while(SystemClock.elapsedRealtime()<until){
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            if(root!=null&&harness.contentEquals(root.getPackageName())){
                AccessibilityNodeInfo[] bottom={null};int[] y={-1};
                matches(root,n->{ if(n.isEditable()&&n.isVisibleToUser()){
                    android.graphics.Rect r=new android.graphics.Rect();n.getBoundsInScreen(r);
                    if(r.bottom>y[0]){y[0]=r.bottom;bottom[0]=n;}
                }return false;});
                if(bottom[0]!=null){
                    Bundle value=new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,reply);
                    if(bottom[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value)){
                        AccessibilityNodeInfo send=button("发送",1000);
                        if(send!=null){click(send);return;}
                    }
                }
            }
            SystemClock.sleep(120);
        }
        throw new IllegalStateException("Ordinary composer did not accept the clarification");
    }
    void openFixture() throws Exception {
        command("am start -W -f 0x10008000 -n "+FIXTURE+"/.FixtureActivity");
        long end=SystemClock.elapsedRealtime()+4000;
        while(SystemClock.elapsedRealtime()<end){if(active(FIXTURE))return;SystemClock.sleep(100);}
        throw new IllegalStateException("Synthetic source not in foreground");
    }
    boolean active(String pkg) {if(Build.VERSION.SDK_INT>=33)ui.clearCache();AccessibilityNodeInfo r=ui.getRootInActiveWindow();return r!=null && pkg.contentEquals(r.getPackageName());}
    AccessibilityNodeInfo button(String s,long ms){return waitNode(n->label(n,s),ms);}
    void openPanel() throws Exception {command("input keyevent KEYCODE_ASSIST");if(button("操作手机",3800)==null&&!ownsRoot()){command("cmd voiceinteraction show");SystemClock.sleep(350);}}
    void enter(String value) {
        if(waitNode(AccessibilityNodeInfo::isEditable,250)==null)click(button("改用键盘",4000));
        Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        long until=SystemClock.elapsedRealtime()+4000;
        do{
            AccessibilityNodeInfo e=waitNode(AccessibilityNodeInfo::isEditable,400);
            if(e!=null&&e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text)&&waitNode(n->n.isEditable()&&label(n,value),400)!=null)return;
        }while(SystemClock.elapsedRealtime()<until);
        throw new IllegalStateException("Could not enter and verify complete synthetic text");
    }
    void voice() throws Exception {
        openFixture();openPanel();
        // No audio or transcript is saved. Start with no typed message, wait for the real
        // provider's recoverable error, then add an unsent fixture draft before selecting Retry.
        phase("await Xiaomi recognition result on fixture");
        AccessibilityNodeInfo retry=waitNode(n->label(n,"重新听写")&&n.getText()!=null,1900);
        if(retry==null){
            AccessibilityNodeInfo mic=button("说话",500);
            if(mic!=null)click(mic);
            retry=waitNode(n->label(n,"重新听写")&&n.getText()!=null,17000);
        }
        if(retry==null){out.putString("voice_outcome","No recoverable error with retry appeared; no speech sample provided");}
        else {
            enter(DRAFT);
            AccessibilityNodeInfo resume=waitNode(n->"继续听写".contentEquals(n.getText()==null?"":n.getText()),3000);
            if(resume==null)throw new IllegalStateException("Error did not offer a retry with the editable draft");
            click(resume);
            out.putBoolean("retry_selected",true);
            if(waitNode(n->label(n,"正在听，说完点发送","改用键盘","正在聆听")||label(n,DRAFT)&&!n.isEditable(),3000)==null)
                throw new IllegalStateException("Retry did not enter listening UI");
            AccessibilityNodeInfo keyboard=button("改用键盘",1500);
            if(keyboard!=null)click(keyboard);
            if(waitNode(n->n.isEditable()&&label(n,DRAFT),4000)==null)throw new IllegalStateException("Synthetic draft not preserved when leaving microphone");
            out.putBoolean("unsent_draft_retained",true);
            out.putString("voice_outcome","Recovery microphone opened; no human speech or transcription accuracy measured");
        }
        closePanel();
    }
    void offlineMic() throws Exception {
        openFixture();openPanel();
        // The same ordinary quick-assistant input. A nonempty disposable draft forces manual
        // dictation even if this owner normally uses hands-free send. Never select Send.
        enter(DRAFT);
        click(waitNode(n->label(n,"继续听写")&&n.getContentDescription()!=null,4000));
        phase("ordinary assistant microphone opened with unsent manual draft");
        long start=SystemClock.elapsedRealtime(),firstMic=0,lastMic=0;int samples=0;
        try {
            do{
                if(waitNode(n->label(n,"重新听写","发生错误")||
                   String.valueOf(n.getText()).contains("系统语音服务中途停止")||
                   String.valueOf(n.getText()).contains("无法连接其服务"),80)!=null)
                    throw new IllegalStateException("Recoverable ASR error returned during local microphone test");
                // Reads only this package's microphone AppOp, never audio, ASR payloads, or other packages.
                boolean active=false;
                try(java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    ui.executeShellCommand("cmd appops get com.androidharness.app RECORD_AUDIO"))))){
                    String line;while((line=br.readLine())!=null)if(line.contains("running")||line.contains("duration=-1"))active=true;
                }
                if(active){long now=SystemClock.elapsedRealtime();if(firstMic==0)firstMic=now;lastMic=now;samples++;}
                if(lastMic-firstMic>=7200)break;
                SystemClock.sleep(230);
            }while(SystemClock.elapsedRealtime()-start<24000);
            out.putLong("harness_microphone_active_span_ms",lastMic-firstMic);out.putInt("active_audio_samples",samples);
            if(lastMic-firstMic<6000)throw new IllegalStateException("No continuous own-process live microphone evidence beyond former five-second failure");
        }finally{
            AccessibilityNodeInfo keyboard=button("改用键盘",1500);if(keyboard!=null)click(keyboard);
        }
        if(waitNode(n->n.isEditable()&&String.valueOf(n.getText()).startsWith(DRAFT),3500)==null)throw new IllegalStateException("Unsent draft not recovered on mic close");
        out.putBoolean("unsent_draft_retained",true);
        closePanel();
    }
    void journey() throws Exception {
        openFixture();openPanel();phase("ordinary assistant compound request");
        enter(TASK);click(button("发送",3000));
        if(button(FIXTURE,6000)==null)throw new IllegalStateException("Review did not name synthetic source");
        AccessibilityNodeInfo managed=button("本次托管并开始",3500);
        if(managed==null)throw new IllegalStateException("Managed run not visible");
        ui.setOnAccessibilityEventListener(e->{
            if(!watching)return;
            String p=String.valueOf(e.getPackageName());long now=SystemClock.elapsedRealtime();
            if(e.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED&&CALC.equals(p)&&calcEvent){
                AccessibilityNodeInfo source=e.getSource();
                if(source!=null&&CALC.contentEquals(source.getPackageName()))for(String k:new String[]{"3","7","×","乘","乘以","1","9","=","等于"})
                    if(label(source,k)){buttonEvents.append(k);break;}
            }
            if(e.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
            if(HOME.equals(p)&&!homeEvent){homeTime=now;homeEvent=true;}
            if(CALC.equals(p)&&homeEvent&&!calcEvent){calcTime=now;calcEvent=true;}
        });
        watching=true; click(managed);submitted=true;phase("managed permission clicked once");
        long end=SystemClock.elapsedRealtime()+230000;boolean posted=false;
        do{
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            AccessibilityNodeInfo r=ui.getRootInActiveWindow();
            if(r!=null){
                String p=String.valueOf(r.getPackageName());
                if(HOME.equals(p)&&!homeEvent){homeTime=SystemClock.elapsedRealtime();homeEvent=true;}
                if(CALC.equals(p)&&homeEvent){
                    if(!calcEvent){calcTime=SystemClock.elapsedRealtime();calcEvent=true;}
                    arithmetic(r);currentCalculatorRows(r);
                }else if(harness.equals(p)){
                    if(!out.containsKey("model")&&matches(r,n->label(n,"gpt-6.2-sol")))out.putString("model","gpt-6.2-sol");
                    if(matches(r,n->{String a=String.valueOf(n.getText());return a.contains("find_on_home")&&a.contains("tap_icon")&&a.contains("verified");}))
                        out.putBoolean("launcher_tool_receipt_in_panel",true);
                    if(matches(r,n->label(n,"在此对话中允许","仅允许一次","允许","授权","允许切换")&&n.isEnabled()))
                        throw new IllegalStateException("Unexpected second approval in managed run; left unapproved");
                    if(calcEvent&&windowResult&&(stages.contains("37×19")||stages.contains("37×19=703")||buttonEvents.toString().matches(".*37(?:×|乘以?)19.*"))&&matches(r,n->{String t=String.valueOf(n.getText());return !n.isEditable()&&t.length()<500&&t.contains("703")&&!t.contains("observationId");})){
                        out.putBoolean("assistant_answer_contains_703",true);completed=true;break;
                    }
                    if(terminalFailure(r)&&button("停止",180)==null)throw new IllegalStateException("Assistant reported task failure; see safe category/status");
                    if(calcEvent&&windowResult&&button("停止",100)==null&&!posted){posted=true;phase("target result confirmed; awaiting assistant answer");}
                }
            }
            boolean before=windowResult;
            if(calcEvent)for(AccessibilityWindowInfo w:ui.getWindows()){
                if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                AccessibilityNodeInfo root=w.getRoot();
                if(root!=null&&CALC.contentEquals(root.getPackageName())){arithmetic(root);currentCalculatorRows(root);}
            }
            if(windowResult&&!before)phase("actual Xiaomi Calculator display = 703");
            SystemClock.sleep(125);
        }while(SystemClock.elapsedRealtime()<end);
        if(!completed)throw new IllegalStateException("Timed out before both target and assistant showed result");
    }
    boolean terminalFailure(AccessibilityNodeInfo r){
        String[][] codes={{"unknown_node","Unknown node."},{"stale","stale observation"},{"launcher_target_not_found","No uniquely matching visible app icon"},{"icon_not_launched","The requested app did not appear"},{"wrong_foreground","unexpected foreground"},{"timeout","Timed out waiting for"},{"need_user","需要你手动"},{"launcher_access","无法找到计算器"},{"provider_auth","401 Unauthorized"}};
        for(String[] c:codes)if(matches(r,n->{String v=String.valueOf(n.getText());return v.length()<480&&v.toLowerCase(java.util.Locale.ROOT).contains(c[1].toLowerCase(java.util.Locale.ROOT));})){
            out.putString("diagnostic_category",c[0]);return true;}
        return false;
    }
    // Never emit other calculator data. Verify outside the digit keys and any history rows.
    void arithmetic(AccessibilityNodeInfo n){
        if(n==null||!CALC.contentEquals(n.getPackageName())||!n.isVisibleToUser())return;
        String id=String.valueOf(n.getViewIdResourceName()).toLowerCase(java.util.Locale.ROOT);
        if(id.contains("history")||id.contains("recycler"))return;
        if(!n.isClickable()||id.contains("result")||id.contains("formula")||id.contains("expression")){
            for(CharSequence t:new CharSequence[]{n.getText(),n.getContentDescription()}){
                if(t==null)continue;String v=t.toString().replaceAll("[\\s,，]","").replace('＊','×').replace('*','×');
                if(v.equals("37")||v.equals("37×")||v.equals("37×1")||v.equals("37×19")||v.equals("37×19=703"))stages.add(v);
                if(v.equals("703")||v.equals("=703")||v.equals("37×19=703")||v.equals("结果703")||v.equals("结果：703")){windowResult=true;}
            }
        }
        for(int i=0;i<n.getChildCount();i++)arithmetic(n.getChild(i));
    }
    boolean matches(AccessibilityNodeInfo n,Predicate<AccessibilityNodeInfo> cond){
        if(n==null||!harness.contentEquals(n.getPackageName()))return false;
        if(n.isVisibleToUser()&&cond.test(n))return true;
        for(int i=0;i<n.getChildCount();i++)if(matches(n.getChild(i),cond))return true;
        return false;
    }
    boolean fixtureAnswer(String v){String plain=v.replaceAll("[\\s*]","");return plain.length()<500&&plain.contains("37")&&plain.contains("19")&&plain.contains("703")&&!plain.contains("observationId");}
    boolean marker(AccessibilityNodeInfo n){String v=String.valueOf(n.getText());return !n.isEditable()&&(v.equals(TASK)||fixtureAnswer(v));}
    void currentCalculatorRows(AccessibilityNodeInfo n){
        if(n==null||!n.isVisibleToUser()||!CALC.contentEquals(n.getPackageName()))return;
        String id=n.getViewIdResourceName();String text;
        if("com.miui.calculator:id/result".equals(id)||"com.miui.calculator:id/expression".equals(id)){
            text=String.valueOf(n.getText()).replaceAll("\\s","");if("=703".equals(text)||"703".equals(text))windowResult=true;
            if("37×19".equals(text)||"37×1".equals(text)||"37×".equals(text)||"37".equals(text))stages.add(text);
        }
        for(int i=0;i<n.getChildCount();i++)currentCalculatorRows(n.getChild(i));
    }
    void closePanel() throws Exception {
        for(int i=0;i<2&&ownsRoot();i++){command("input keyevent KEYCODE_BACK");SystemClock.sleep(350);}
    }
    private static final String ISLAND_REQUEST="请操作手机：在当前页面连续完成两步：把留言填成「海盐」，再点击下方「白鹭」的选择。看到「第二步 · 核对」就结束并告诉我结果，不要提交。";
    static final String[] ISLAND_TITLES={"正在规划下一步","正在查看页面","正在点选控件","正在输入文字","正在执行操作","正在执行 1 / 2","正在执行 2 / 2","正在核对结果","已完成","任务已结束 · 请查看结果","需要你处理","未完成","已停止","手机操作已暂停"};
    private boolean islandReceipt, islandTerminal,islandReturned;
    void island() throws Exception {
        openFixture(); openPanel(); enter(ISLAND_REQUEST);click(button("发送",3200));
        if(button(FIXTURE,5500)==null)throw new IllegalStateException("Review did not name synthetic source");
        click(button("本次托管并开始",3500));submitted=true;phase("managed synthetic two-step task started");
        long end=SystemClock.elapsedRealtime()+195000;Set<String>seen=new LinkedHashSet<>();
        do{
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            for(AccessibilityWindowInfo w:ui.getWindows()){
                AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
                if(FIXTURE.contentEquals(r.getPackageName())) {
                    if(hasFixture(r,"选项：白鹭\n留言：海盐")&&hasFixture(r,"第二步 · 核对"))islandReceipt=true;
                    if(hasFixture(r,"第三步 · 已收到"))throw new IllegalStateException("Unexpected fixture submission");
                }
                if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&harness.contentEquals(r.getPackageName())){
                    for(String t:ISLAND_TITLES)if(matches(r,n->label(n,t,"任务进度："+t))){
                        if(seen.add(t)){out.putString("island_timeline",seen.toString());sendStatus(0,new Bundle(out));}
                        boolean terminal=t.equals("已完成")||t.equals("未完成")||t.startsWith("任务已结束")||t.equals("已停止");
                        if(t.matches("正在执行 [12] / 2"))out.putBoolean("model_used_sequence",true);
                        if(terminal){islandTerminal=true;out.putString("terminal",t);}
                        String shot=terminal?"island-terminal.png":"island-running.png";
                        if(!out.containsKey(shot)&&islandCrop(w,shot))out.putBoolean(shot,true);
                        if(!terminal&&!out.containsKey("fixture-context.png")&&fixtureContextCrop(w))out.putBoolean("fixture-context.png",true);
                    }
                }
                if(harness.contentEquals(r.getPackageName())&&w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY){
                    if(matches(r,n->label(n,"android_sequence","Android sequence")))out.putBoolean("sequence_tool_visible_in_chat",true);
                    if(matches(r,n->{String t=String.valueOf(n.getText());return !n.isEditable()&&!ISLAND_REQUEST.equals(t)&&t.length()<500&&t.contains("海盐")&&t.contains("白鹭")&&!t.contains("observationId");}))islandReturned=true;
                    if(matches(r,n->label(n,"仅允许一次","在此对话中允许","允许")&&n.isEnabled()))throw new IllegalStateException("Unexpected extra approval for noncommitting fixture steps; not granted");
                }
            }
            if(islandReceipt&&islandTerminal){completed=true;break;}
            SystemClock.sleep(95);
        }while(SystemClock.elapsedRealtime()<end);
        out.putBoolean("actual_fixture_review_has_both_values",islandReceipt);out.putBoolean("returned_assistant_contains_both_values",islandReturned);
        if(!completed)throw new IllegalStateException("Missing expected fixture review or terminal island");
    }
    boolean hasFixture(AccessibilityNodeInfo n,String t){
        if(n==null||!FIXTURE.contentEquals(n.getPackageName()))return false;
        if(n.isVisibleToUser()&&label(n,t))return true;
        for(int i=0;i<n.getChildCount();i++)if(hasFixture(n.getChild(i),t))return true;return false;
    }
    boolean islandCrop(AccessibilityWindowInfo w,String name) throws Exception {
        // Only the island's own rectangle; no other personal application pixels or system bars.
        android.graphics.Rect b=new android.graphics.Rect();w.getBoundsInScreen(b);
        if(b.isEmpty()||b.width()>780||b.height()>550)return false;
        out.putString(name+"_bounds",b.flattenToString());
        return saveCrop(b,name);
    }
    boolean fixtureContextCrop(AccessibilityWindowInfo island) throws Exception {
        AccessibilityNodeInfo r=ui.getRootInActiveWindow();if(r==null||!FIXTURE.contentEquals(r.getPackageName()))return false;
        android.graphics.Rect app=new android.graphics.Rect(),bubble=new android.graphics.Rect();r.getBoundsInScreen(app);island.getBoundsInScreen(bubble);
        if(app.isEmpty()||!app.contains(bubble))return false;
        app.bottom=Math.min(app.bottom,app.top+Math.round(app.width()*.69f));
        for(AccessibilityWindowInfo w:ui.getWindows()){
            if(w.getId()==island.getId()||w.getId()==r.getWindowId())continue;
            android.graphics.Rect other=new android.graphics.Rect();w.getBoundsInScreen(other);
            if(android.graphics.Rect.intersects(app,other))return false;
        }
        return saveCrop(app,"fixture-context.png");
    }
    boolean saveCrop(android.graphics.Rect b,String name) throws Exception {
        android.graphics.Bitmap screen=ui.takeScreenshot();if(screen==null)return false;
        try {
            if(!b.intersect(0,0,screen.getWidth(),screen.getHeight()))return false;
            android.graphics.Bitmap crop=android.graphics.Bitmap.createBitmap(screen,b.left,b.top,b.width(),b.height());
            try(java.io.FileOutputStream os=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))) {
                return crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,os);
            }finally{if(crop!=screen)crop.recycle();}
        }finally{screen.recycle();}
    }
    /** Inspect only fixed, non-personal signal classes when an actual journey stalls. No input. */
    void inspect() {
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();
        for(AccessibilityWindowInfo w:ui.getWindows()){
            if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
            AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
            String p=String.valueOf(r.getPackageName());
            if(CALC.equals(p)){out.putBoolean("calculator_window_present",true);arithmetic(r);currentCalculatorRows(r);calcFlags(r);}
            if(harness.equals(p)){
                out.putBoolean("assistant_window_present",true);terminalFailure(r);
                String[][] terms={{"active","停止"},{"awaiting_approval","仅允许一次"},{"awaiting_grant","在此对话中允许"},{"privacy_in_assistant","隐私"},{"requires_agreement","协议"},{"model_reports_703","703"},{"assistant_reports_blocked","无法"},{"launcher_receipt","tap_icon"},{"model_pending","正在处理"}};
                for(String[] t:terms)if(matches(r,n->String.valueOf(n.getText()).contains(t[1])||label(n,t[1])))out.putBoolean(t[0],true);
            }
        }
    }
    void calcFlags(AccessibilityNodeInfo n){
        if(n==null||!n.isVisibleToUser()||!CALC.contentEquals(n.getPackageName()))return;
        for(CharSequence v:new CharSequence[]{n.getText(),n.getContentDescription()}){
            if(v==null)continue;String s=v.toString();
            if(s.contains("隐私")||s.contains("用户协议"))out.putBoolean("calculator_privacy_gate_text",true);
            if(s.equals("同意")||s.contains("同意并继续"))out.putBoolean("calculator_consent_button",true);
            if(s.matches("[0-9]")&&n.isClickable())out.putBoolean("calculator_numeric_buttons",true);
            if(s.contains("703"))out.putBoolean("calculator_contains_703",true);
            if(s.replace(" ","").matches(".*37[×xX*乘]19.*"))out.putBoolean("calculator_contains_expression",true);
                if(s.replaceAll("\\s","").matches(".*37[×xX*乘]19\\D{0,16}703.*"))
                    out.putBoolean("calculator_same_visible_label_37_times_19_equals_703",true);
                if(s.contains("703")||s.replace(" ","").matches(".*37[×xX*乘]19.*")){
                    String id=n.getViewIdResourceName();
                    if(id!=null&&id.matches("com\\.miui\\.calculator:id/[a-zA-Z0-9_]+"))out.putString(s.contains("703")?"result_node_id":"expression_node_id",id);
                    if(s.matches("[\\s0-9+×÷=().:*xX\\-乘为于果结算式是：，。]+")) {
                        if(s.replaceAll("[^0-9]","").matches("703|3719|3719703")) out.putString(s.contains("703")?"target_result_label":"target_expression_label",s);
                    }
                }
        }
        for(int i=0;i<n.getChildCount();i++)calcFlags(n.getChild(i));
    }
    void history() throws Exception {
        // Only after a successful journey ending in OUR synthetic conversation. Do not reset someone else's chat.
        closePanel();
        openPanel();
        if(waitNode(this::marker,5500)==null)throw new IllegalStateException("First re-summons did not show synthetic context; no reset attempted");
        out.putBoolean("first_resummon_restored_context",true);
        AccessibilityNodeInfo answer=waitNode(n->!n.isEditable()&&fixtureAnswer(String.valueOf(n.getText())),3000);
        if(answer!=null){out.putBoolean("restored_assistant_answer_contains_703",true);String s=answer.getText().toString();
            if(s.matches("[已从桌面打开计算器，。结果是在0123456789×=\\s]+"))out.putString("synthetic_answer",s);}
        if(waitNode(n->label(n,"gpt-6.2-sol"),200)!=null)out.putString("model","gpt-6.2-sol");
        closePanel();openPanel();
        if(waitNode(this::marker,5500)==null)throw new IllegalStateException("Second re-summons did not show synthetic context; no reset attempted");
        out.putBoolean("second_resummon_restored_context",true);
        enter(RESET);click(button("发送",3000));
        SystemClock.sleep(1100);
        if(!ownsRoot()||waitNode(this::marker,1500)!=null||button("操作手机",1500)==null)throw new IllegalStateException("Fresh assistant context not confirmed");
        out.putBoolean("explicit_reset_removed_synthetic_context",true);
        closePanel();
    }
}
