package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Ordinary assistant input → named map confirmation. No GPS, accounts, map screenshots or navigation. */
public final class MapChatProbe extends ReadPageProbe {
    private static final String QUERY = "纸月测试公园", MAP = "com.autonavi.minimap";
    private static final String FIXTURE = "com.androidharness.fixture.phoneuse";
    private String mode;
    private boolean mayExpand;
    private boolean leavePriorResult;
    private boolean live;
    @Override public void onCreate(Bundle args) {
        mode = args.getString("mapCase", "");
        live = "one-explicit-device-trial".equals(args.getString("live"));
        mayExpand = "true".equals(args.getString("allowExpand"));
        leavePriorResult = "true".equals(args.getString("leavePriorTestResult"));
        super.onCreate(args);
    }
    @Override public void onStart() {
        Bundle evidence = new Bundle();
        boolean sent = false, chosen = false;
        try {
            boolean missing = mode.equals("baidu-missing"), natural = mode.equals("natural-preview");
            boolean fixture = mode.equals("fixture-map"), direct = mode.equals("amap-direct");
            if (!missing && !natural && !fixture && !direct)
                throw new IllegalArgumentException("Choose a known mapCase");
            if (!live) throw new IllegalArgumentException("All map cases use the selected model; explicit live trial opt-in required");
            if (!harness.matches("com\\.androidharness\\.app(\\.debug)?") ||
                    fixture && !(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu")))
                throw new IllegalStateException("Unknown assistant, or fixture-map requested outside the test emulator");
            if (getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked())
                throw new IllegalStateException("Phone is locked");
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            if (missing && leavePriorResult) {
                // When rechecking the identical search on a new build, leave only our already
                // visible result. Never open history, clear app data, or dismiss another app.
                boolean left=false;
                for(int i=0;i<2;i++){
                    if(Build.VERSION.SDK_INT>=33)ui.clearCache();
                    AccessibilityNodeInfo root=ui.getRootInActiveWindow();
                    if(root==null || !MAP.contentEquals(root.getPackageName()))break;
                    MapResult r=new MapResult();inspectMap(root,MAP,r);
                    if(r.query==0)break;
                    command("input keyevent KEYCODE_BACK"); SystemClock.sleep(550); left=true;
                }
                if(Build.VERSION.SDK_INT>=33)ui.clearCache();
                AccessibilityNodeInfo current=ui.getRootInActiveWindow();
                if(current!=null && MAP.contentEquals(current.getPackageName())){
                    MapResult after=new MapResult();inspectMap(current,MAP,after);
                    evidence.putBoolean("prior_map_query_visible",after.query>0);
                }
                evidence.putBoolean("left_prior_test_result",left);
            }
            // Finish any preceding fixture preview before summoning a new session over Home.
            for(int i=0;i<2 && ownsRoot();i++){command("input keyevent KEYCODE_BACK");SystemClock.sleep(450);}
            // The preview case runs over an inert page.
            if(natural) { command("input keyevent KEYCODE_HOME"); SystemClock.sleep(700); }
            if (natural || fixture)
                command("am start -W -f 0x10008000 -n " + FIXTURE + "/.DirectActionFixtureActivity --ez offer_fixture_map " + fixture);
            if (!natural) { command("input keyevent KEYCODE_HOME"); SystemClock.sleep(950); }
            else SystemClock.sleep(350);
            command("input keyevent KEYCODE_ASSIST");
            evidence.putString("stage", "open ordinary input");
            Rect initialHandle = dragHandle(2300);
            if(initialHandle!=null) evidence.putInt("initial_handle_top_px",initialHandle.top);
            AccessibilityNodeInfo field = waitNode(AccessibilityNodeInfo::isEditable,1000);
            if (field == null) {
                AccessibilityNodeInfo type = waitNode(n -> label(n, "改用键盘","Type instead"), 4000);
                if (type == null && !ownsRoot()) {
                    // The launcher can still be settling after Home during a cold assistant-service bind.
                    command("input keyevent KEYCODE_ASSIST");
                    type = waitNode(n -> label(n,"改用键盘","Type instead"), 3500);
                }
                click(type);
                field=waitNode(AccessibilityNodeInfo::isEditable,4000);
            }
            if (field==null) throw new IllegalStateException("Ordinary assistant text input missing");
            int window = field.getWindowId();
            String request = natural ? "纸月测试公园这个地方，我想直接在高德里看一下" :
                    missing ? "帮我打开百度地图搜索纸月测试公园" :
                    fixture ? "请用地图搜索纸月测试公园" : "帮我打开高德地图然后搜索纸月测试公园";
            Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,request);
            boolean entered = false;
            long editingUntil = SystemClock.elapsedRealtime() + 3000;
            do {
                field = waitNode(AccessibilityNodeInfo::isEditable,300);
                if (field!=null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text)) {
                    entered = waitNode(n -> n.isEditable() && label(n,request),500) != null;
                }
                if (!entered) SystemClock.sleep(100);
            } while (!entered && SystemClock.elapsedRealtime() < editingUntil);
            if (!entered) throw new IllegalStateException("Cannot enter task");
            AtomicBoolean mayLaunch = new AtomicBoolean(direct), premature = new AtomicBoolean(false);
            ui.setOnAccessibilityEventListener(e -> {
                if (!mayLaunch.get() && e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        Arrays.asList(MAP,"com.baidu.BaiduMap","com.android.chrome","com.android.browser","com.microsoft.emmx")
                            .contains(String.valueOf(e.getPackageName()))) premature.set(true);
            });
            evidence.putString("stage", "send requested words");
            click(waitNode(n -> label(n,"发送","Send"),4000));
            sent = true;
            evidence.putString("ordinary_input",request);
            if (direct) {
                // The send button was the last interaction. Do not tap any confirmation, map or result.
                verifyMap(MAP, false, evidence);
                evidence.remove("stage");
                return;
            }
            String mapLabel = fixture ? "手机操作验证" : "高德地图";
            Predicate<AccessibilityNodeInfo> isChoice = n -> {
                String t = n.getText()==null ? "" : n.getText().toString();
                return t.contains(mapLabel) && (t.equals(mapLabel) || t.length()<=32 && (t.matches("^(改用|用).+搜索$") ||
                    t.matches("(?i)^(use|search|switch).+")));
            };
            AccessibilityNodeInfo choice = null;
            boolean approvedSuggestion = false;
            boolean expanded = false;
            long began = SystemClock.elapsedRealtime();
            long until = SystemClock.elapsedRealtime()+65000;
            do {
                choice = waitNode(isChoice,220);
                if (choice != null) break;
                if(mayExpand && !expanded && SystemClock.elapsedRealtime()-began>3500 && ownsRoot()) {
                    AccessibilityNodeInfo handle=waitNode(n -> label(n,"拖动以调整大小","Drag to resize"),280);
                    if(handle!=null){
                        Rect b=new Rect(); handle.getBoundsInScreen(b);
                        command("input swipe "+b.centerX()+" "+b.centerY()+" "+b.centerX()+" "+Math.max(160,b.centerY()/3)+" 450");
                        expanded=true;
                    }
                }
                if (natural && !approvedSuggestion) {
                    AccessibilityNodeInfo allow = waitNode(n -> label(n,"允许","Allow"),60);
                    if (allow!=null) {
                        // This tool can only display a confirmation. Never grant a map launch here.
                        if (waitNode(n -> label(n,"suggest_app_action","Suggest app action"),650)==null ||
                                waitNode(n -> String.valueOf(n.getText()).contains("map_search"),650)==null)
                            throw new IllegalStateException("An unrelated model tool requested permission; not approved");
                        click(allow); approvedSuggestion=true;
                    }
                }
            } while (SystemClock.elapsedRealtime()<until);
            evidence.putString("stage","inspect named choice");
            if (choice == null) throw new IllegalStateException("No named map action card (no substitute or link was opened)");
            if (choice.getWindowId() != window) throw new IllegalStateException("Map confirmation opened a different window");
            evidence.putString("confirmation", String.valueOf(choice.getText()));
            evidence.putBoolean("manually_expanded",expanded);
            evidence.putString("window_ids",window+" -> "+choice.getWindowId());
            AccessibilityNodeInfo indication = waitNode(n -> {
                String t = n.getText()==null ? "" : n.getText().toString();
                if (t.equals(request)) return false;
                return missing ? t.contains("百度地图") && (t.contains("未安装") || t.contains("没有安装") || t.contains("找不到") || t.contains("未找到") || t.contains("没找到"))
                        : t.contains(QUERY);
            },3000);
            if (indication==null) throw new IllegalStateException("Missing target or search query was not explained");
            evidence.putString("ordinary_input",request);
            if (premature.get()) throw new IllegalStateException("Map or browser started before confirmation");
            if (natural || missing) {
                // Finding the genuine native confirmation without clicking it must not launch a different application.
                AccessibilityNodeInfo cancel=waitNode(n -> label(n,"取消","Cancel"),3000);
                if(cancel==null)throw new IllegalStateException("The cancel button is not visible");
                if(missing)captureAssistantBody(evidence);
                click(cancel);
                evidence.putString("outcome",missing ? "Baidu unavailable; alternative not launched" : "AMap proposed; not launched");
                if(ownsRoot()) command("input keyevent KEYCODE_BACK");
            } else {
                mayLaunch.set(true);
                click(choice); chosen=true;
                verifyMap(FIXTURE,true,evidence);
            }
            evidence.remove("stage");
        } catch(Exception e) {
            evidence.putString("failure",e.toString());
            if(ui!=null) { AccessibilityNodeInfo n=ui.getRootInActiveWindow(); evidence.putString("foreground_package",
                    n==null ? "(none)" : String.valueOf(n.getPackageName())); }
        } finally {
            if(ui!=null)ui.setOnAccessibilityEventListener(null);
            evidence.putBoolean("sent_in_normal_input",sent);
            evidence.putBoolean("clicked_named_choice",chosen);
            finish(evidence.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK,evidence);
        }
    }
    private Rect dragHandle(long timeout) {
        AccessibilityNodeInfo n=waitNode(v -> label(v,"拖动以调整大小","Drag to resize"),timeout);
        if(n==null)return null;
        Rect b=new Rect();n.getBoundsInScreen(b);return b;
    }
    private void captureAssistantBody(Bundle evidence) throws Exception {
        Rect drag=dragHandle(800);
        AccessibilityNodeInfo close=waitNode(n->label(n,"关闭","Close"),800);
        if(drag==null || close==null)return;
        Rect footer=new Rect();close.getBoundsInScreen(footer);
        evidence.putInt("loaded_handle_top_px",drag.top);
        android.graphics.Bitmap raw=ui.takeScreenshot();
        if(raw==null)return;
        try {
            int inset=Math.max(12,raw.getWidth()/30), top=Math.max(0,drag.top), bottom=Math.min(footer.bottom+4,raw.getHeight());
            if(bottom<=top)return;
            // Keep the assistant controls only; home icons, notification content and map locations are outside the crop.
            android.graphics.Bitmap card=android.graphics.Bitmap.createBitmap(raw,inset,top,raw.getWidth()-2*inset,bottom-top);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(
                    getTargetContext().getExternalFilesDir(null),"missing-map-panel.png"))){
                card.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
            }finally{card.recycle();}
        }finally{raw.recycle();}
    }
    private void verifyMap(String p, boolean fixture, Bundle evidence) {
        long end=SystemClock.elapsedRealtime()+23000;
        boolean sawPackage=false, sawQuery=false;
        while (SystemClock.elapsedRealtime()<end) {
            if(Build.VERSION.SDK_INT>=33)ui.clearCache();
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            if(root!=null && p.contentEquals(root.getPackageName())){
                sawPackage=true;
                MapResult r=new MapResult(); inspectMap(root,p,r);
                sawQuery |= r.query > 0;
                if(fixture ? r.fixtureReceipt : r.query>0 && (r.resultMarker || r.query>1)) {
                    evidence.putString("foreground_package",p);
                    evidence.putBoolean("query_on_page",true);
                    evidence.putInt("matching_text_controls",r.query);
                    evidence.putInt("distinct_matching_labels",r.unique.size());
                    evidence.putBoolean("result_controls",r.resultMarker);
                    evidence.putString("outcome",fixture ? "Synthetic map displayed exact query" : "AMap displayed the requested search page");
                    return;
                }
                if(r.privacy)throw new IllegalStateException("Map privacy decision required; no agreement given");
            }
            SystemClock.sleep(200);
        }
        throw new IllegalStateException("No verified search result; foreground map="+sawPackage+"; query visible="+sawQuery);
    }
    private static final class MapResult { int query; boolean resultMarker, privacy, fixtureReceipt; java.util.Set<String> unique=new java.util.HashSet<>(); }
    private void inspectMap(AccessibilityNodeInfo n,String p,MapResult r) {
        if(n==null || !p.contentEquals(n.getPackageName()))return;
        if(n.isVisibleToUser()) {
            String t = n.getText()==null ? "" : n.getText().toString();
            String d = n.getContentDescription()==null ? "" : n.getContentDescription().toString();
            if(t.contains(QUERY)||d.contains(QUERY)) r.query++;
            if(t.contains(QUERY))r.unique.add(t);
            if(d.contains(QUERY))r.unique.add(d);
            if(t.equals("Public map receipt: "+QUERY)) r.fixtureReceipt=true;
            String viewId = n.getViewIdResourceName();
            if(viewId!=null && viewId.matches("(?i).*(search_result|result_list|poi_list|poi_search_result).*")) r.resultMarker=true;
            for(String marker : Arrays.asList("筛选","智能排序","综合排序","全部分类","搜索结果","共找到","查看详情","查看更多地点","搜索此区域","去这里","附近优先"))
                if(t.contains(marker)||d.contains(marker)) r.resultMarker=true;
            if(t.contains("同意并继续")||t.equals("同意隐私政策"))r.privacy=true;
        }
        for(int i=0;i<n.getChildCount();i++)inspectMap(n.getChild(i),p,r);
    }
}
