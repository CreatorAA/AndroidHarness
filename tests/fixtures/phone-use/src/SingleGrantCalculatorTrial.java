package com.androidharness.fixture.phoneuse;

import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import static com.androidharness.fixture.phoneuse.CalculatorJourneyProbe.CALC;
import static com.androidharness.fixture.phoneuse.CalculatorJourneyProbe.HOME;
import static com.androidharness.fixture.phoneuse.ReadPageProbe.click;
import static com.androidharness.fixture.phoneuse.ReadPageProbe.label;

/** One ordinary command from MIUI Home; the probe never activates a calculator key or changes settings. */
final class SingleGrantCalculatorTrial {
    private static final String REQUEST = "开计算器计算20 × 18";
    private final CalculatorJourneyProbe p;

    SingleGrantCalculatorTrial(CalculatorJourneyProbe probe) { p = probe; }

    // Report only the fixed launcher/assistant input state. No account, screen, or transcript is logged.
    void inspectControls() {
        if (Build.VERSION.SDK_INT >= 33) p.ui.clearCache();
        AccessibilityNodeInfo active = p.ui.getRootInActiveWindow();
        p.out.putBoolean("assistant_root_active", active != null && p.harness.contentEquals(active.getPackageName()));
        for (AccessibilityWindowInfo w : p.ui.getWindows()) {
            AccessibilityNodeInfo r = w.getRoot(); if (r == null || !p.harness.contentEquals(r.getPackageName())) continue;
            p.out.putBoolean("assistant_window", true);
            if (p.matches(r, AccessibilityNodeInfo::isEditable)) p.out.putBoolean("assistant_has_editor",true);
            if (p.matches(r,n -> n.isEditable() && label(n, REQUEST))) p.out.putBoolean("request_in_editor",true);
            if (p.matches(r,n -> !n.isEditable() && label(n, REQUEST))) p.out.putBoolean("request_outside_editor",true);
            String[][] markers = {{"keyboard","改用键盘"},{"send","发送"},{"managed_start","本次托管并开始"},
                    {"normal_suggestion","操作手机"},{"denied","拒绝"},{"close","关闭"},{"ongoing","停止"},
                    {"mic","说话"},{"cancel","取消"},{"start_once","允许并开始"},{"answer_button","回答"},
                    {"continue_dictation","继续听写"},{"miui_home",HOME},{"page_actions","查看本页动作"},
                    {"synthetic_request",REQUEST},{"synthetic_reset",CalculatorJourneyProbe.RESET}};
            for (String[] pair:markers) if (p.matches(r,n->label(n,pair[1]))) p.out.putBoolean("visible_" + pair[0],true);
        }
    }

    void run() throws Exception {
        // Do not inspect launcher children, move the user out of an app, or open any system permission screen.
        if (!p.active(HOME)) throw new IllegalStateException("Expected idle Xiaomi Home before starting");
        p.out.putString("source_package", HOME);
        p.openPanel(); p.phase("assistant invoked from Xiaomi Home");
        if (p.button("gpt-6.2-sol", 100) != null) p.out.putString("model", "gpt-6.2-sol");
        if (p.waitNode(AccessibilityNodeInfo::isEditable, 250) == null) {
            AccessibilityNodeInfo keyboard = p.button("改用键盘", 5000);
            if (keyboard == null) { inspectControls(); throw new IllegalStateException("No ordinary keyboard action in assistant"); }
            click(keyboard);
        }
        AccessibilityNodeInfo field = p.waitNode(AccessibilityNodeInfo::isEditable, 3000);
        if (field == null || (!field.isShowingHintText() && field.getText() != null && field.getText().length() != 0 &&
                !CalculatorJourneyProbe.DRAFT.contentEquals(field.getText()) && !label(field,"问问这个屏幕","继续追问")))
            throw new IllegalStateException("Existing draft left unchanged");
        p.phase("ordinary assistant input ready");
        p.enter(CalculatorJourneyProbe.RESET);
        AccessibilityNodeInfo resetSend = p.button("发送", 3000);
        if (resetSend == null) { inspectControls(); throw new IllegalStateException("No send after local new-chat command"); }
        click(resetSend); SystemClock.sleep(500);
        p.phase("fresh assistant chat ready");
        p.enter(REQUEST); click(p.button("发送", 2500));
        AccessibilityNodeInfo start = p.button("本次托管并开始", 7500);
        if (start == null) throw new IllegalStateException("No one-time managed review for the original wording");
        if (p.button(HOME, 700) == null) throw new IllegalStateException("Initial review did not identify MIUI Home");
        p.ui.setOnAccessibilityEventListener(e -> {
            if (!p.watching || e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    !CALC.contentEquals(String.valueOf(e.getPackageName()))) return;
            AccessibilityNodeInfo key = e.getSource();
            if (key == null) return;
            for (String k : new String[]{"2","0","1","8","×","乘","乘以","=","等于"}) {
                if (label(key,k)) { p.buttonEvents.append(k); break; }
            }
        });
        p.watching = true; click(start); p.submitted = true;
        p.out.putInt("managed_starts", 1); p.out.putInt("additional_approval_requests", 0);
        p.phase("one managed start for user's original calculator request");
        boolean leftInitial = false, expr = false, value = false, terminal = false, reportedCalc = false;
        long until = SystemClock.elapsedRealtime() + 180_000;
        do {
            if (Build.VERSION.SDK_INT >= 33) p.ui.clearCache();
            boolean initialVisible = false;
            for (AccessibilityWindowInfo w : p.ui.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (CALC.contentEquals(r.getPackageName())) {
                    p.calcEvent = true;
                    if (!reportedCalc) { reportedCalc = true; p.phase("MIUI Calculator in view"); }
                    // Only compare these two known field IDs to the expected values; never export other calculations.
                    expr |= fieldIs(r, "expression", "20[×xX*]18"); value |= fieldIs(r, "result", "=?360");
                }
                if (!p.harness.contentEquals(r.getPackageName())) continue;
                initialVisible |= p.matches(r,n -> label(n,"本次托管并开始","允许并开始"));
                boolean extra = p.matches(r,n -> n.isEnabled() &&
                        label(n,"仅允许一次","在此对话中允许","批准并执行","批准此操作？","允许此更改操作你的手机？"));
                if ((leftInitial && initialVisible) || extra) {
                    p.out.putInt("additional_approval_requests", 1);
                    String which = p.matches(r,n -> String.valueOf(n.getText()).contains("将本次任务切换到另一个应用")) ?
                            "retarget" : initialVisible ? "another_task_start" : "action_or_chat";
                    p.out.putString("additional_approval_kind", which);
                    throw new IllegalStateException("Unexpected additional approval; not granted");
                }
                terminal |= p.matches(r,n -> label(n,"已完成","任务进度：已完成","任务已结束 · 请查看结果","任务进度：任务已结束 · 请查看结果"));
                if (!p.out.containsKey("model") && p.matches(r,n -> label(n,"gpt-6.2-sol"))) p.out.putString("model","gpt-6.2-sol");
            }
            if (!initialVisible) leftInitial = true;
            if (verified(p.buttonEvents.toString(), expr, value, terminal, p.out.getInt("additional_approval_requests"))) break;
            SystemClock.sleep(110);
        } while (SystemClock.elapsedRealtime() < until);
        p.out.putBoolean("calculator_expression_20x18",expr);
        p.out.putBoolean("calculator_verified_360",value);
        p.out.putBoolean("terminal_island_observed",terminal);
        if (!verified(p.buttonEvents.toString(), expr, value, terminal, p.out.getInt("additional_approval_requests")))
            throw new IllegalStateException("Additional approval or missing live calculation, target result, or completion");
        p.completed = true;
    }

    /** This is an approval-count trial. The target must actually operate on 18 and commit 20×18=360;
     * keep every observed key as separate evidence rather than inferring how it obtained the first operand. */
    static boolean verified(String keys, boolean expression, boolean value, boolean terminal, int additionalApprovals) {
        return additionalApprovals == 0 && expression && value && terminal && keys.matches(".*(?:×|乘以?)18(?:=|等于).*");
    }

    private static boolean fieldIs(AccessibilityNodeInfo r, String field, String expected) {
        for (AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByViewId(CALC + ":id/" + field))
            if (n.isVisibleToUser() && String.valueOf(n.getText()).replace(" ","").matches(expected)) return true;
        return false;
    }
}
