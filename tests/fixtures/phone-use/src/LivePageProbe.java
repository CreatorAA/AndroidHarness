package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;
import java.util.regex.Pattern;

/** Opt-in, single real model turn on this inert fixture. No access to provider settings or other apps. */
public final class LivePageProbe extends ReadPageProbe {
    private static final String PKG = "com.androidharness.fixture.phoneuse";
    private static final String INSTRUCTION = "请使用当前页面提供的 show_test_receipt 动作显示一次测试回执，不要模拟点击或重复执行。若回调返回 shown，请用一句话报告结果，最后不加间隔地写出这两个词：夹具、核验成功。";
    private boolean live;

    @Override public void onCreate(Bundle args) {
        live = "one-authorized-real-model-turn".equals(args.getString("live"));
        super.onCreate(args);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean submitted = false, actionAllowed = false, completed = false;
        try {
            if (!live || !PKG.equals(target) || !"com.androidharness.app".equals(harness))
                throw new IllegalArgumentException("Explicit real-model fixture opt-in required");
            if (getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked()) throw new IllegalStateException("Phone is locked");
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            command("am start -W -f 0x10008000 -n " + PKG + "/.DirectActionFixtureActivity --ez offer_fixture_map false");
            awaitReceipt("Direct Action fixture: ready");
            command("input keyevent KEYCODE_ASSIST");
            openTaskReview(waitPanelEntry(8000));
            if (waitNode(n -> label(n,PKG),5000) == null) throw new IllegalStateException("Wrong or missing target");
            AccessibilityNodeInfo field = waitNode(AccessibilityNodeInfo::isEditable,4000);
            Bundle text = new Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,INSTRUCTION);
            if (field == null || !field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text))
                throw new IllegalStateException("Could not enter fixture task");
            click(waitNode(n -> label(n,"允许并开始","Allow and start"),4000));
            submitted=true;
            Pattern model = Pattern.compile("(?i)^(?:.{0,30}[·/ ]+)?((?:gpt|claude|gemini|deepseek|qwen|glm|kimi|doubao|mimo|grok)[- .]?[a-zA-Z0-9_.:+ -]{1,70})$");
            long end = SystemClock.elapsedRealtime()+185000;
            long start = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() < end) {
                AccessibilityNodeInfo n = waitNode(v -> label(v,"在此对话中允许","Allow in this chat"),70);
                if (n != null) { click(n); SystemClock.sleep(350); continue; }
                n = waitNode(v -> label(v,"仅允许一次","Allow once"),70);
                if (n != null) {
                    AccessibilityNodeInfo preview = waitNode(v -> {
                        String s = String.valueOf(v.getText()).replaceAll("\\s+"," ");
                        return s.contains(PKG + " · show_test_receipt") && s.contains("{}");
                    }, 900);
                    if (preview == null || actionAllowed) throw new IllegalStateException("Unexpected or repeated action confirmation; not approved");
                    click(n); actionAllowed=true;
                    result.putString("confirmed_native_action", "show_test_receipt {}");
                    sendStatus(0,result); SystemClock.sleep(350); continue;
                }
                n = waitNode(v -> label(v,"允许","Allow"),70);
                if (n != null) {
                    // The existing general tool prompt precedes the more specific native-action gate.
                    // Its human-readable caption uses spaces; CodePanel shows the underlying tool id.
                    boolean read = waitNode(v -> label(v,"android_observe","Android observe","android_capabilities","Android capabilities"),500) != null;
                    boolean nativeRequest = !read && waitNode(v -> label(v,"android_action","Android action"),500) != null &&
                            waitNode(v -> String.valueOf(v.getText()).contains("page_action"),500) != null;
                    if (!read && !nativeRequest) throw new IllegalStateException("Unrecognized general tool prompt; not approved");
                    result.putString("general_confirmation", read ? "read-only phone tool" : "page_action; exact target still requires separate approval");
                    sendStatus(0,result);
                    click(n); SystemClock.sleep(350); continue;
                }
                if (!result.containsKey("model")) {
                    n=waitNode(v -> v.getText()!=null && model.matcher(v.getText()).matches(),70);
                    if(n!=null) result.putString("model",n.getText().toString());
                }
                n=waitNode(v -> {
                    String s=String.valueOf(v.getText());
                    return !s.equals(INSTRUCTION) && s.contains("夹具核验成功");
                }, 80);
                if(n!=null && actionAllowed) {
                    result.putString("assistant_answer",n.getText().toString().substring(0,Math.min(350,n.getText().length())));
                    completed=true; break;
                }
                if (SystemClock.elapsedRealtime()-start > 5500) {
                    n=waitNode(v -> {
                        String s=String.valueOf(v.getText()).toLowerCase(Locale.ROOT);
                        return s.length()<450 && (s.contains("api key") || s.contains("api 密钥") || s.contains("api 密匙") ||
                            s.contains("no model") || s.contains("401") || s.contains("429") || s.contains("未选择模型") ||
                            s.contains("请先配置") || s.contains("后台运行"));
                    },60);
                    if(n!=null) throw new IllegalStateException("Model/setup error displayed; no further request sent");
                }
                SystemClock.sleep(180);
            }
            if (!completed) throw new IllegalStateException("No verified fixture answer before timeout");
            if (ownsRoot()) command("input keyevent KEYCODE_BACK");
            awaitReceipt("Direct Action receipt: shown once");
            result.putString("target_receipt","Direct Action receipt: shown once");
        } catch (Exception e) {
            result.putString("failure", e.toString());
            // Stop this specific, newly-started task if it is waiting for any other approval.
            if (submitted && !completed && ui != null) try {
                AccessibilityNodeInfo stop = waitNode(n -> label(n,"停止","Stop"),1000);
                if(stop!=null) click(stop);
                else command("input keyevent KEYCODE_ASSIST");
            } catch (Exception ignored) {}
        } finally {
            result.putBoolean("submitted",submitted); result.putBoolean("action_approved_once",actionAllowed);
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK,result);
        }
    }
    private void awaitReceipt(String expected) {
        long end=SystemClock.elapsedRealtime()+6000;
        while(SystemClock.elapsedRealtime()<end){
            if(android.os.Build.VERSION.SDK_INT>=33) ui.clearCache();
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            if(root!=null && PKG.contentEquals(root.getPackageName()) && containsFixtureText(root,expected))return;
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("Fixture did not display the expected receipt");
    }
    private boolean containsFixtureText(AccessibilityNodeInfo n,String value) {
        if(n==null || !PKG.contentEquals(n.getPackageName())) return false;
        if(label(n,value))return true;
        for(int i=0;i<n.getChildCount();i++) if(containsFixtureText(n.getChild(i),value))return true;
        return false;
    }
}
