package com.androidharness.fixture.phoneuse;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import static com.androidharness.fixture.phoneuse.ReadPageProbe.*;

/** Physical release acceptance: the driver may type in Harness, never in the target.
 * Read the rendered synthetic form in its own process even when the assistant is above it. */
final class AutoJsRoundTripTrial {
    static final String REQUEST = "请操作手机：用 AutoJs6 脚本完成这个测试表单，不要逐个用内置工具代点。" +
        "观察页面后，在脚本库新建「示例表单验收-%s」（不覆盖已有脚本），检查它，然后用 autojs_run 按名称执行：" +
        "把留言填成「蓝桥27」，点上方青竹的「选择」，核对留言和选项。" +
        "再用 autojs_run 实际运行匿名脚本：点「返回修改」，把原留言替换成「海盐42」，改选下方白鹭，核对新的留言和选项。" +
        "停在第二次核对页，不要提交。告诉我两次的结果。";
    final CalculatorJourneyProbe p;
    final AtomicInteger stage = new AtomicInteger();
    AutoJsRoundTripTrial(CalculatorJourneyProbe p) { this.p = p; }

    private String read(FixtureActivity form, int id) {
        TextView view = form.findViewById(id);
        return view == null ? "" : view.getText().toString();
    }
    private void observe(FixtureActivity form) {
        String page = read(form, R.id.page_title);
        if (stage.get() == 0 && "第二步 · 核对".equals(page) &&
                "选项：青竹\n留言：蓝桥27".equals(read(form,R.id.review))) stage.set(1);
        else if (stage.get() == 1 && "第一步 · 填写留言".equals(page) &&
                "蓝桥27".equals(read(form,R.id.message))) stage.set(2);
        else if (stage.get() == 2 && "第二步 · 核对".equals(page) &&
                "选项：白鹭\n留言：海盐42".equals(read(form,R.id.review))) stage.set(3);
    }
    void run() throws Exception {
        Instrumentation.ActivityMonitor monitor = p.addMonitor(FixtureActivity.class.getName(), null, false);
        final FixtureActivity form;
        try {
            p.openFixture();
            form = (FixtureActivity)p.waitForMonitorWithTimeout(monitor, 4000);
        } finally { p.removeMonitor(monitor); }
        if (form == null) throw new IllegalStateException("No fresh synthetic fixture Activity");
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> observe(form);
        p.runOnMainSync(() -> form.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener));
        int before = form.getPreferences(Context.MODE_PRIVATE).getInt("count", 0);
        try {
            p.openPanel();
            AccessibilityNodeInfo keyboard = p.button("改用键盘", 160);
            if (keyboard != null) click(keyboard);
            AccessibilityNodeInfo field = p.waitNode(AccessibilityNodeInfo::isEditable, 1300);
            if (field != null && !field.isShowingHintText() && field.getText() != null && field.getText().length() > 0)
                throw new IllegalStateException("Existing unsent assistant draft; left it intact");
            String label = Long.toString(System.currentTimeMillis(), 36);
            p.out.putString("synthetic_script", "示例表单验收-" + label);
            p.enter(String.format(java.util.Locale.ROOT,REQUEST,label));
            p.out.putLong("managed_wall_ms", System.currentTimeMillis());
            click(p.button("发送", 3200)); p.submitted = true;
            p.out.putInt("managed_starts", 0);
            p.phase("submitted to real model; follow owner's current task-start setting");
            boolean leftCard = false, terminal = false, savedOnce = false;
            int published = 0;
            long firstApprovalAt = 0;
            boolean approvalVisible = false;
            long revealAttemptAt=0;
            long until = SystemClock.elapsedRealtime() + 300000;
            while (SystemClock.elapsedRealtime() < until) {
                if (form.getPreferences(Context.MODE_PRIVATE).getInt("count",0) != before)
                    throw new IllegalStateException("Test form submitted unexpectedly");
                int seen = stage.get();
                if (seen != published) { p.out.putInt("rendered_stage", seen); p.phase(new String[]{"", "first review: bamboo / blue bridge 27",
                        "original message retained on edit page", "final review: egret / sea salt 42"}[seen]); published = seen; }
                if (Build.VERSION.SDK_INT >= 33) p.ui.clearCache();
                boolean card = false, pendingApproval=false;
                for (AccessibilityWindowInfo w : p.ui.getWindows()) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null || !p.harness.contentEquals(root.getPackageName())) continue;
                    card |= p.matches(root, n -> n.isEnabled() && label(n, "本次托管并开始", "允许并开始"));
                    if (p.matches(root,n -> n.isEnabled() && label(n,"仅允许一次","在此对话中允许","批准并执行","批准此操作？"))) {
                        pendingApproval=true;
                        boolean tool=p.matches(root,n -> label(n,"autojs_scripts"));
                        boolean args=p.matches(root,n -> safeSaveArgs(n.getText(),p.out.getString("synthetic_script")));
                        AccessibilityNodeInfo allow = p.button("允许",250);
                        if (!savedOnce && tool && args && allow==null && SystemClock.elapsedRealtime()-revealAttemptAt>1200){
                            // A tall code preview can leave the action below the panel viewport.
                            AccessibilityNodeInfo outer=p.waitNode(AccessibilityNodeInfo::isScrollable,250);
                            if(outer!=null && outer.getWindowId()==root.getWindowId())
                                outer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                            revealAttemptAt=SystemClock.elapsedRealtime();
                        }
                        if(allow!=null && p.out.getString("allow_bounds")==null && !savedOnce){
                            android.graphics.Rect b=new android.graphics.Rect();allow.getBoundsInScreen(b);
                            p.out.putString("allow_bounds",b.flattenToString());
                            AccessibilityNodeInfo title=p.button("批准此操作？",100);
                            if(title!=null){title.getBoundsInScreen(b);p.out.putString("approval_title_bounds",b.flattenToString());}
                            p.phase("one-time approval button visible; no approval granted yet");
                        }
                        if (!approvalVisible){
                            if (savedOnce)throw new IllegalStateException("Another approval requested after the one synthetic save; not granted");
                            firstApprovalAt=SystemClock.elapsedRealtime();
                            p.out.putBoolean("save_tool_label_visible",tool);
                            p.out.putBoolean("expected_save_args_visible",args);
                            p.phase("approval displayed; validating exact new script before one-time grant");
                        }
                        if (!savedOnce && tool && args && allow != null && allow.getWindowId() == root.getWindowId()) {
                            click(allow); savedOnce = true; p.out.putInt("specific_script_save_grants",1);
                            p.phase("approved one local save of this new synthetic script");
                            SystemClock.sleep(650);
                        } else if (!savedOnce && SystemClock.elapsedRealtime()-firstApprovalAt>90000) {
                            throw new IllegalStateException("Approval not identified as the expected synthetic save; not granted");
                        }
                    }
                    if (p.matches(root,n -> label(n,"gpt-6.2-sol"))) p.out.putString("visible_model","gpt-6.2-sol");
                    if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        terminal |= p.matches(root,n -> label(n,"已完成","任务进度：已完成","任务已结束 · 请查看结果","任务进度：任务已结束 · 请查看结果"));
                        if (p.matches(root,n -> label(n,"未完成","任务进度：未完成","已停止","任务进度：已停止"))) {
                            p.completed = true; throw new IllegalStateException("Model task stopped before confirmed completion");
                        }
                    }
                }
                if(approvalVisible && !pendingApproval && !savedOnce){
                    // The operator may visually review and allow this one synthetic save if its
                    // code is painted but unavailable to accessibility. Disappearance alone is
                    // not evidence it was allowed, and does not authorize any later card.
                    savedOnce=true;p.out.putBoolean("first_approval_closed_by_operator",true);
                }
                approvalVisible=pendingApproval;
                if (card && p.out.getInt("managed_starts") == 0 && stage.get() == 0) {
                    if (p.button(CalculatorJourneyProbe.FIXTURE,450)==null)
                        throw new IllegalStateException("Task card did not identify the synthetic app");
                    click(p.button("本次托管并开始",650)); p.out.putInt("managed_starts",1); leftCard=false;
                    p.phase("one explicit managed start");
                } else if (leftCard && card) throw new IllegalStateException("Second task card; not granted");
                if (p.out.getInt("managed_starts")>0 && !card) leftCard = true;
                if (stage.get() == 3 && terminal) break;
                if (terminal && stage.get() < 3) { p.completed = true; throw new IllegalStateException("Model ended before both actual reviews"); }
                SystemClock.sleep(140);
            }
            p.out.putBoolean("terminal_observed", terminal);
            if (stage.get() != 3 || !terminal) throw new IllegalStateException("Missing observed target steps or terminal");
            final boolean[] exact = {false};
            p.runOnMainSync(() -> exact[0] = "第二步 · 核对".equals(read(form,R.id.page_title)) &&
                "选项：白鹭\n留言：海盐42".equals(read(form,R.id.review)));
            if (!exact[0]) throw new IllegalStateException("Fixture did not stay on final review");
            p.completed = true;
            p.out.putLong("trial_end_wall_ms",System.currentTimeMillis());
            // Inspect the two receipts via the user's ordinary tool-details UI, not private storage.
            new AutoJsReceiptInspector(p).inspect(p.out.getString("synthetic_script"));
        } finally {
            p.runOnMainSync(() -> form.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener));
            p.out.putInt("rendered_stage", stage.get());
            p.out.putInt("submit_count_change", form.getPreferences(Context.MODE_PRIVATE).getInt("count",0) - before);
            if (!p.out.containsKey("trial_end_wall_ms")) p.out.putLong("trial_end_wall_ms", System.currentTimeMillis());
        }
    }
    /** Accept only full JSON for the fresh title. Reads/waits are harmless; the two possible
     * mutations are restricted to this known field/value and this fixture's upper choice. */
    private static boolean safeSaveArgs(CharSequence displayed, String expected) {
        if (displayed == null || displayed.length() > 2600) return false;
        try {
            org.json.JSONObject json = new org.json.JSONObject(displayed.toString());
            if (!"save".equals(json.optString("action")) || !expected.equals(json.optString("name"))) return false;
            String input = json.getString("source");
            boolean fill=false, click=false;
            for (String raw : input.split("\n")) {
                String s=raw.trim().replace("'", "\"").replace("com.androidharness.fixture.phoneuse:id/","");
                if (s.isEmpty() || s.startsWith("//")) continue;
                if (s.matches("(?:id|text|desc)\\(\"[^\"]+\"\\)\\.(?:waitFor\\(\\)|findOne\\(\\d*\\));?")) continue;
                if (!fill && !click && s.matches("(?:id\\(\"message\"\\)|text\\(\"留言内容\"\\))\\.findOne\\(\\d*\\)\\.setText\\(\"蓝桥27\"\\);?")) { fill=true; continue; }
                if (fill && !click && s.matches("id\\(\"choose_first\"\\)\\.findOne\\(\\d*\\)\\.click\\(\\);?")) { click=true; continue; }
                return false;
            }
            return fill && click;
        } catch (org.json.JSONException ignored) { return false; }
    }
}
