package com.androidharness.fixture.phoneuse;

import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONObject;
import static com.androidharness.fixture.phoneuse.ReadPageProbe.*;

/** Read-only evidence after the task's terminal event. Open the current turn's normal
 * activity disclosures. Never export the conversation or inspect unrelated conversations. */
final class AutoJsReceiptInspector {
    private final CalculatorJourneyProbe p;
    private boolean named, anonymous;
    AutoJsReceiptInspector(CalculatorJourneyProbe p) { this.p=p; }
    private void visit(AccessibilityNodeInfo n, Predicate<AccessibilityNodeInfo> condition, List<AccessibilityNodeInfo> out) {
        if (n == null || !p.harness.contentEquals(n.getPackageName())) return;
        if (n.isVisibleToUser() && condition.test(n)) out.add(n);
        for (int i=0; i<n.getChildCount(); i++) visit(n.getChild(i),condition,out);
    }
    private List<AccessibilityNodeInfo> nodes(Predicate<AccessibilityNodeInfo> f) {
        if (Build.VERSION.SDK_INT >= 33) p.ui.clearCache();
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        visit(p.ui.getRootInActiveWindow(),f,items); return items;
    }
    private String txt(AccessibilityNodeInfo n) {return n.getText()==null?"":n.getText().toString();}
    private List<AccessibilityNodeInfo> runs() {return nodes(n->label(n,"已运行 AutoJs6 脚本"));}
    private boolean latestTurnThought(AccessibilityNodeInfo n) {
        return label(n,"思考过程") || txt(n).startsWith("思考了 ");
    }
    private JSONObject object(String key) {
        for (AccessibilityNodeInfo n:nodes(v->txt(v).startsWith("{") && txt(v).length()<4000)) {
            try {JSONObject o=new JSONObject(txt(n)); if(o.has(key))return o;}catch(Exception ignored){}
        }
        return null;
    }
    void inspect(String name) throws Exception {
        p.openPanel();
        // Private transcript text is never emitted. Only navigate if the actual test turn is visible.
        if (p.waitNode(n->!n.isEditable() && (txt(n).contains(name) ||
                (txt(n).contains("蓝桥27") && txt(n).contains("海盐42"))),6500)==null) {
            p.out.putString("receipt_inspection","Synthetic turn not visible"); return;
        }
        if (runs().isEmpty()) {
            List<AccessibilityNodeInfo> headers=nodes(this::latestTurnThought);
            if(headers.isEmpty()){p.out.putString("receipt_inspection","Activity disclosure not in view");return;}
            click(headers.get(headers.size()-1));
            SystemClock.sleep(400);
        }
        // Use the turn-level full view, which contains this turn alone and can be paged safely.
        List<AccessibilityNodeInfo> full=nodes(n->"打开完整视图".contentEquals(n.getContentDescription()==null?"":n.getContentDescription()));
        if(full.isEmpty()){p.out.putString("receipt_inspection","Turn reader not visible");return;}
        click(full.get(full.size()-1));SystemClock.sleep(400);
        for(int page=0;page<26 && !(named && anonymous);page++){
            int count=runs().size();
            for(int i=0;i<count && !(named && anonymous);i++) {
                List<AccessibilityNodeInfo> current=runs();if(i>=current.size())break;
                click(current.get(i)); SystemClock.sleep(220);
                AccessibilityNodeInfo details=p.waitNode(n->label(n,"打开工具详情"),600);
                if(details==null)continue;
                // The inline result is a regular text node. Some OEMs expose the later
                // selectable code reader as one non-text node, despite drawing the JSON.
                JSONObject inline=object("completedSteps");
                click(details);SystemClock.sleep(220);
                if(p.button("autojs_run",800)!=null && p.button("输入",400)!=null) {
                    JSONObject result=object("completedSteps");
                    if(result==null)result=inline;
                    if(result!=null && "ran".equals(result.optString("status")) &&
                            result.optInt("completedSteps",0)>0 && result.optInt("completedSteps")==result.optInt("totalSteps")) {
                        click(p.button("输入",600));SystemClock.sleep(200);
                        JSONObject input=object("name");
                        if(input!=null && name.equals(input.optString("name")) && !input.has("source")) {
                            named=true; note("named",result);
                        }else{
                            input=object("source");
                            if(input!=null && !input.has("name") && input.optString("source").contains("海盐42")){
                                anonymous=true; note("anonymous",result);
                            }
                        }
                    }
                }
                if(p.button("输入",150)!=null && p.button("输出",150)!=null){
                    p.command("input keyevent 4");SystemClock.sleep(220);
                }
                // Close the row that we just expanded so subsequent rows stay discoverable.
                if(p.button("打开工具详情",120)!=null) {
                    current=runs();if(i<current.size())click(current.get(i));
                    SystemClock.sleep(150);
                }
            }
            if(named && anonymous)break;
            List<AccessibilityNodeInfo> scroll=nodes(n->n.isScrollable());
            if(scroll.isEmpty() || !scroll.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;
            SystemClock.sleep(300);
        }
        p.out.putString("receipt_inspection",named && anonymous?"Both verified in tool details":"Incomplete; inspect task activity");
    }
    private void note(String kind,JSONObject result){
        p.out.putInt(kind+"_autojs_run_completed_steps",result.optInt("completedSteps"));
        p.out.putInt(kind+"_autojs_run_verified_checks",result.optInt("verifiedChecks"));
        p.phase(kind+" autojs_run: status ran, actual input and output matched");
    }
}
