package com.androidharness.fixture.phoneuse;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read a single stopped tool call through the foreground assistant's own disclosures. No ADB
 * commands, app launches, coordinates, clipboard, screenshots, approvals, or text entry. */
final class StoppedApprovalInspector {
    private static final String TURN="思考过程",STOP="活动已停止",DONE="已完成活动",FAIL="活动暂不可用",FULL="打开完整视图",DETAIL="打开工具详情";
    private final UiAutomation ui;
    private final String harness,request,expectedApp;
    private final Bundle out;
    private String stage="need_current_harness_panel";
    private String wantedObservation,wantedNode;
    private final List<Tap> auditTaps=new ArrayList<>();
    private boolean auditing;
    private String historicalSuffix;
    private static final String EXHIBIT_QUESTION="帮我去这份日程里查一下周日下午那场摄影展几点停止入场";
    private static final String SYNTHETIC_QUESTION="帮我自动化分析一下这个人在惊讶什么";
    StoppedApprovalInspector afterSyntheticRuns(String verifiedSuffix){
        if(verifiedSuffix==null||!verifiedSuffix.matches("[A-Za-z][A-Za-z0-9_]{0,23}"))throw new IllegalArgumentException("Previously verified suffix required");
        historicalSuffix=verifiedSuffix;return this;
    }
    StoppedApprovalInspector(UiAutomation ui,String harness,String request,Bundle out){
        this(ui,harness,request,"com.tencent.mobileqq",out);
    }
    StoppedApprovalInspector(UiAutomation ui,String harness,String request,String expectedApp,Bundle out){
        this.ui=ui;this.harness=harness;this.request=request;this.expectedApp=expectedApp;this.out=out;
    }
    private AccessibilityNodeInfo root(){
        if(Build.VERSION.SDK_INT>=33)ui.clearCache();
        AccessibilityNodeInfo r=ui.getRootInActiveWindow();
        if(r!=null&&harness.contentEquals(r.getPackageName()))return r;
        // IME can have accessibility focus. Inspect only roots positively owned by Harness.
        for(AccessibilityWindowInfo w:ui.getWindows()){
            if(w.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            r=w.getRoot();
            if(r!=null&&harness.contentEquals(r.getPackageName())&&(w.isFocused()||w.isActive()||
                    "VoiceInteractionSession".contentEquals(w.getTitle()==null?"":w.getTitle())))return r;
        }
        return null;
    }
    private void walk(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> nodes){
        if(n==null||!harness.contentEquals(n.getPackageName()))return;
        if(n.isVisibleToUser())nodes.add(n);
        for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),nodes);
    }
    private List<AccessibilityNodeInfo> rows(){
        List<AccessibilityNodeInfo> list=new ArrayList<>();walk(root(),list);return list;
    }
    private static String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private static boolean matches(AccessibilityNodeInfo n,String literal){
        if(literal.equals(text(n))||literal.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return true;
        // Compose may merge a label and a second caption into the same semantics node.
        return ("\n"+text(n)+"\n").contains("\n"+literal+"\n");
    }
    private static Rect bounds(AccessibilityNodeInfo n){Rect b=new Rect();n.getBoundsInScreen(b);return b;}
    private static AccessibilityNodeInfo last(List<AccessibilityNodeInfo> a,String label){
        AccessibilityNodeInfo result=null;for(AccessibilityNodeInfo n:a)if(matches(n,label))result=n;return result;
    }
    private static AccessibilityNodeInfo latestAnchor(List<AccessibilityNodeInfo> a,String req){
        AccessibilityNodeInfo result=null;for(AccessibilityNodeInfo n:a)if(!n.isEditable()&&text(n).equals(req)&&
                (result==null||bounds(n).centerY()>=bounds(result).centerY()))result=n;return result;
    }
    private static void require(boolean b){if(!b)throw new IllegalStateException();}
    private void tap(AccessibilityNodeInfo n,String... allow){
        require(n!=null&&harness.contentEquals(n.getPackageName())&&n.isVisibleToUser());
        boolean ok=false;for(String s:allow)ok|=matches(n,s);require(ok);ReadPageProbe.click(n);
        SystemClock.sleep(430);
    }
    private boolean scroll(List<AccessibilityNodeInfo> nodes,boolean forward){
        AccessibilityNodeInfo best=null;
        for(AccessibilityNodeInfo n:nodes)if(n.isScrollable()&&
                (best==null||bounds(n).height()>bounds(best).height()))best=n;
        if(best==null)return false;
        boolean moved=best.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        SystemClock.sleep(260);return moved;
    }
    private AccessibilityNodeInfo outerFull(List<AccessibilityNodeInfo> nodes,AccessibilityNodeInfo thinking){
        if(thinking==null)return null;
        Rect tr=bounds(thinking);AccessibilityNodeInfo pick=null;
        for(AccessibilityNodeInfo n:nodes)if(matches(n,FULL)){
            Rect rr=bounds(n);
            if(Math.abs(rr.centerY()-tr.centerY())<=Math.max(tr.height()/2,rr.height()/2)&&rr.centerX()>tr.centerX())pick=n;
        }
        return pick;
    }
    private static boolean tail(List<AccessibilityNodeInfo> nodes){
        return last(nodes,"在应用中继续")!=null&&last(nodes,"新问题")!=null;
    }
    private static boolean hasTranscript(List<AccessibilityNodeInfo> nodes){
        return tail(nodes)||last(nodes,TURN)!=null||latestAnchor(nodes,EXHIBIT_QUESTION)!=null||latestAnchor(nodes,SYNTHETIC_QUESTION)!=null;
    }
    private boolean scrollTranscript(List<AccessibilityNodeInfo> nodes,boolean forward,int window){
        // The question marks and the footer live in PanelBody's outer vertical scroll, never
        // in a tool disclosure, a horizontal suggestion strip, or the conversation picker.
        AccessibilityNodeInfo representative=last(nodes,"在应用中继续");
        if(representative==null)representative=last(nodes,TURN);
        if(representative==null)representative=latestAnchor(nodes,EXHIBIT_QUESTION);
        if(representative==null)representative=latestAnchor(nodes,SYNTHETIC_QUESTION);
        AccessibilityNodeInfo outer=null;
        for(AccessibilityNodeInfo n=representative;n!=null&&n.getWindowId()==window&&harness.contentEquals(n.getPackageName());n=n.getParent())
            if(n.isScrollable()&&bounds(n).height()>100)outer=n;
        if(outer==null){
            for(AccessibilityNodeInfo n:nodes)if(n.getWindowId()==window&&n.isScrollable()&&bounds(n).height()>130&&
                    (outer==null||bounds(n).height()>bounds(outer).height()))outer=n;
        }
        if(outer==null)return false;
        boolean moved=outer.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        SystemClock.sleep(320);return moved;
    }
    private List<AccessibilityNodeInfo> locateHistorical(){
        List<AccessibilityNodeInfo> nodes=rows();boolean voice=last(nodes,"改用键盘")!=null;
        out.putBoolean("started_with_keyboard_switch",voice);
        for(int i=0;i<10&&!hasTranscript(nodes);i++){SystemClock.sleep(180);nodes=rows();}
        if(!hasTranscript(nodes)&&last(nodes,"改用键盘")!=null){
            tap(last(nodes,"改用键盘"),"改用键盘");out.putBoolean("switched_panel_to_keyboard_no_entry",true);
            for(int i=0;i<10&&!hasTranscript(nodes);i++){SystemClock.sleep(160);nodes=rows();}
        }
        stage="current_panel_has_no_visible_transcript";require(hasTranscript(nodes));
        out.putBoolean("current_panel_has_visible_transcript",true);
        AccessibilityNodeInfo pane=root();require(pane!=null);int window=pane.getWindowId();
        int budget=10,forward=0,back=0;stage="current_transcript_end_not_verified";
        while(!tail(nodes)&&budget>0&&forward<3){
            require(scrollTranscript(nodes,true,window));budget--;forward++;nodes=rows();pane=root();require(pane!=null&&pane.getWindowId()==window);
        }
        out.putBoolean("started_from_known_transcript_tail",tail(nodes));require(tail(nodes));
        boolean exhibition=false,synthetic=false;
        for(;;){
            // Walk bottom to top, including within a page. Never pass a newer exact match to
            // inspect an older retry; reject it if its order or its actual prior signature differs.
            List<AccessibilityNodeInfo> markers=new ArrayList<>();
            for(AccessibilityNodeInfo n:nodes)if(!n.isEditable()&&(request.equals(text(n))||
                    EXHIBIT_QUESTION.equals(text(n))||SYNTHETIC_QUESTION.equals(text(n))))markers.add(n);
            markers.sort((x,y)->Integer.compare(bounds(y).centerY(),bounds(x).centerY()));
            for(AccessibilityNodeInfo n:markers){
                String s=text(n);
                if(s.equals(request)){
                    out.putInt("history_pages_back",back);out.putBoolean("later_exhibition_question_seen",exhibition);
                    out.putBoolean("later_synthetic_quote_question_seen",synthetic);
                    stage="latest_exact_question_without_later_trial_anchor";require(exhibition||synthetic);
                    out.putBoolean("newest_exact_match_precedes_later_trials",true);return nodes;
                }
                if(EXHIBIT_QUESTION.equals(s))exhibition=true;if(SYNTHETIC_QUESTION.equals(s))synthetic=true;
            }
            out.putBoolean("later_exhibition_question_seen",exhibition);out.putBoolean("later_synthetic_quote_question_seen",synthetic);
            stage="latest_question_not_in_bounded_current_transcript";
            if(budget==0||!scrollTranscript(nodes,false,window))throw new IllegalStateException();
            budget--;back++;out.putInt("history_pages_back",back);
            nodes=rows();pane=root();require(pane!=null&&pane.getWindowId()==window);
        }
    }
    private void openTurn() {
        List<AccessibilityNodeInfo> nodes=rows();
        if(historicalSuffix!=null){
            AccessibilityNodeInfo active=ui.getRootInActiveWindow();
            out.putBoolean("active_accessibility_root_is_harness",active!=null&&harness.contentEquals(active.getPackageName()));
            out.putBoolean("harness_quick_panel_accessible",!nodes.isEmpty());
            boolean ime=false;for(AccessibilityWindowInfo w:ui.getWindows())ime|=w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD;
            out.putBoolean("keyboard_window_present",ime);
        }
        require(!nodes.isEmpty());stage="exact_question_not_visible";
        // The previous inspector can have left its two nested readers displayed. Return only
        // through verified Harness tool/turn dialogs, without reading any arguments yet.
        if(last(nodes,"输入")!=null&&last(nodes,"输出")!=null){
            String title=readerTitle(nodes);
            if("android_action".equals(title)||"android_observe".equals(title)){closeReader(title);nodes=rows();}
        }
        if(latestAnchor(nodes,request)==null&&last(nodes,TURN)!=null&&last(nodes,"关闭")!=null&&
                (last(nodes,STOP)!=null||last(nodes,DONE)!=null)){
            closeReader(TURN);nodes=rows();
        }
        if(historicalSuffix!=null)nodes=locateHistorical();
        AccessibilityNodeInfo anchor=latestAnchor(nodes,request);
        // Stay in this transcript, at most two pages up from the current position. Only test
        // equality with the supplied question; never export or select another turn's text.
        for(int k=0;historicalSuffix==null&&anchor==null&&k<2&&scroll(nodes,false);k++){nodes=rows();anchor=latestAnchor(nodes,request);}
        require(anchor!=null);out.putBoolean("exact_question_anchor",true);
        int at=nodes.indexOf(anchor),once=nodes.indexOf(last(nodes,"仅允许一次"));
        int heading=nodes.indexOf(last(nodes,"允许此更改操作你的手机？"));
        if(heading>at&&once>heading){
            String category="no_explicit_category_verified";
            for(int j=heading+1;j<once;j++){
                String message=text(nodes.get(j));
                if(message.startsWith("允许这一次提交吗？"))category="submit";
                else if(message.startsWith("允许执行这个动作一次吗？"))category="unspecified_action";
            }
            out.putString("approval_preview_category",category);
        }
        stage="no_activity_bound_to_question";
        AccessibilityNodeInfo thinking=null;int i=nodes.indexOf(anchor);
        for(int j=i+1;j<nodes.size();j++){
            AccessibilityNodeInfo next=nodes.get(j);
            if(historicalSuffix!=null&&!next.isEditable()&&(request.equals(text(next))||EXHIBIT_QUESTION.equals(text(next))||SYNTHETIC_QUESTION.equals(text(next))))break;
            if(matches(next,TURN)){thinking=next;break;}
        }
        require(thinking!=null);
        AccessibilityNodeInfo full=outerFull(nodes,thinking);
        if(full==null){
            tap(thinking,TURN);
            for(int k=0;k<5&&full==null;k++){
                nodes=rows();AccessibilityNodeInfo bound=null;
                if(thinking.refresh()&&thinking.isVisibleToUser())bound=thinking;
                if(bound==null){AccessibilityNodeInfo a=latestAnchor(nodes,request);if(a!=null)
                    for(int j=nodes.indexOf(a)+1;j<nodes.size();j++)if(matches(nodes.get(j),TURN)){bound=nodes.get(j);break;}}
                full=outerFull(nodes,bound);
                if(full==null)SystemClock.sleep(150);
            }
        }
        stage="bound_turn_full_view_unavailable";tap(full,FULL);
        nodes=rows();require(last(nodes,TURN)!=null&&last(nodes,"关闭")!=null);
    }
    private AccessibilityNodeInfo openTool(String label,int previousIndex){
        List<AccessibilityNodeInfo> a=rows();require(last(a,TURN)!=null&&last(a,"关闭")!=null);
        AccessibilityNodeInfo target;
        if(STOP.equals(label)){
            AccessibilityNodeInfo latest=last(a,"最新");if(latest!=null)tap(latest,"最新");
            for(int i=0;i<8&&scroll(rows(),true);i++){/* last row, in this one turn only */}
            a=rows();target=last(a,STOP);
        }else{
            // Enumerate backwards from the latest stopped row in the bound full-turn reader,
            // never from an unrelated turn. Compare returned observation IDs, not row labels.
            target=null;
            for(int k=0;k<10&&scroll(rows(),true);k++){}
            LinkedHashSet<AccessibilityNodeInfo> seen=new LinkedHashSet<>();
            for(int i=0;i<10;i++){
                a=rows();int ix=i==0?a.indexOf(last(a,STOP)):a.size();if(ix<0)break;
                for(int j=ix-1;j>=0;j--)if(matches(a.get(j),DONE)&&seen.add(a.get(j))&&seen.size()==previousIndex+1){
                    target=a.get(j);break;
                }
                if(target!=null||!scroll(a,false))break;
            }
        }
        require(target!=null);showToolReader(target,label);return target;
    }
    private void showToolReader(AccessibilityNodeInfo target,String label){
        tap(target,label);
        List<AccessibilityNodeInfo> a=rows();AccessibilityNodeInfo button=null;
        for(AccessibilityNodeInfo n:a)if(matches(n,DETAIL)){
            if(button!=null&&!bounds(n).equals(bounds(button)))throw new IllegalStateException();
            button=n;
        }
        if(button==null){
            // The text and icon can be merged, dropping the icon's contentDescription.
            for(AccessibilityNodeInfo n:a)if(matches(n,FULL)){if(button!=null)throw new IllegalStateException();button=n;}
        }
        tap(button,DETAIL,FULL);
    }
    private JSONObject json(String tool,String tab) throws Exception{
        List<AccessibilityNodeInfo> a=rows();
        require(last(a,tool)!=null&&last(a,"输入")!=null&&last(a,"输出")!=null);
        tap(last(a,tab),tab);
        // Chunks are successive Text elements in ToolActivityReader's LazyColumn. Their raw
        // contents are local temporaries; never log parse errors (they may quote chat content).
        LinkedHashSet<String> parts=new LinkedHashSet<>();int bytes=0;
        for(int page=0;page<35;page++){
            a=rows();require(last(a,tool)!=null&&last(a,"输入")!=null&&last(a,"输出")!=null);
            int tabsY=bounds(last(a,"输出")).bottom;
            for(AccessibilityNodeInfo n:a){
                if(n.getChildCount()!=0||bounds(n).bottom<=tabsY)continue;
                String s=text(n);if(s.isEmpty()||matches(n,"最新")||matches(n,"关闭")||matches(n,"输入")||matches(n,"输出"))continue;
                if(parts.isEmpty()&&!s.trim().startsWith("{"))continue;
                if(parts.add(s))bytes+=s.length();
            }
            require(bytes<=100000);
            StringBuilder blob=new StringBuilder();for(String s:parts)blob.append(s);
            if("android_observe".equals(tool)&&blob.length()>0){
                JSONObject minimal=auditing?auditObservation(blob.toString()):matchPrefix(blob.toString());
                if(minimal!=null)return minimal;
            }
            if(blob.length()>0&&!(auditing&&"android_observe".equals(tool)))try{return new JSONObject(blob.toString());}catch(org.json.JSONException incomplete){/* bounded scroll */}
            if(!scroll(a,true))break;
        }
        return null;
    }
    // A long observation often occupies many screens or ends in an on-device output limit.
    // Once its header and the requested node are complete, later unrelated messages add no
    // evidence. JsonReader respects escaping inside message text; never match raw substrings.
    private JSONObject matchPrefix(String prefix){
        try(android.util.JsonReader reader=new android.util.JsonReader(new java.io.StringReader(prefix))){
            reader.beginObject();String obs="",pkg="";
            while(reader.hasNext()){
                String key=reader.nextName();
                if(key.equals("observationId")){obs=reader.nextString();if(!obs.equals(wantedObservation))return new JSONObject().put("observationId",obs);}
                else if(key.equals("packageName"))pkg=reader.nextString();
                else if(key.equals("nodes")&&obs.equals(wantedObservation)&&pkg.equals("com.tencent.mobileqq")){
                    reader.beginArray();
                    while(reader.hasNext()){
                        reader.beginObject();JSONObject one=new JSONObject();
                        while(reader.hasNext()){
                            String k=reader.nextName();
                            if(k.equals("id")||k.equals("text")||k.equals("description")||k.equals("resourceId"))one.put(k,reader.nextString());
                            else if(k.equals("clickable"))one.put(k,reader.nextBoolean());
                            else reader.skipValue();
                        }
                        reader.endObject();
                        if(wantedNode.equals(one.optString("id")))return new JSONObject().put("observationId",obs)
                                .put("packageName",pkg).put("nodes",new JSONArray().put(one));
                    }
                    reader.endArray();
                }else reader.skipValue();
            }
        }catch(Exception ignored){/* underlying record can cross a chunk boundary */}
        return null;
    }
    private void closeReader(String title){
        List<AccessibilityNodeInfo> a=rows();require(last(a,title)!=null);
        if(!TURN.equals(title)&&last(a,"输入")!=null&&last(a,"输出")!=null){
            // The actual focused tool dialog is positively identified. Use Back once, never
            // from the ordinary assistant or QQ, and verify that we returned to the same turn.
            AccessibilityNodeInfo focused=ui.getRootInActiveWindow();
            require(focused!=null&&harness.contentEquals(focused.getPackageName())&&focused.getWindowId()==last(a,title).getWindowId());
            require(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            SystemClock.sleep(450);a=rows();
            require(last(a,TURN)!=null&&last(a,"输入")==null);return;
        }
        AccessibilityNodeInfo close=last(a,"关闭");require(close!=null);
        tap(close,"关闭");
    }
    private String readerTitle(List<AccessibilityNodeInfo> a){
        AccessibilityNodeInfo in=last(a,"输入");if(in==null||last(a,"输出")==null)return null;
        for(AccessibilityNodeInfo n:a)if(bounds(n).centerY()<bounds(in).centerY()&&text(n).matches("[a-z][a-z0-9_]{1,64}"))return text(n);
        return null;
    }
    private static final class Tap{
        final String obs,node,intent,row;
        final boolean coordinates,verifiedReceipt;
        Tap(JSONObject in,String row,JSONObject result){
            obs=in.optString("observationId");node=in.optString("node");intent=in.optString("intent");this.row=row;
            coordinates=in.has("x")||in.has("y");verifiedReceipt=result!=null&&obs.equals(result.optString("observationId"))&&
                    "verified".equals(result.optString("status"));
        }
    }
    private boolean wants(String obs,String node){
        for(Tap t:auditTaps)if(obs.equals(t.obs)&&(node==null||node.equals(t.node)))return true;
        return false;
    }
    // referenceEntries follows nodes in the current wire format. A prefix ending after a
    // matching node cannot establish the absence of the field. Accept absence only after
    // parsing the closing brace. Retain no message/description/title or script contents.
    private JSONObject auditObservation(String prefix){
        try(android.util.JsonReader r=new android.util.JsonReader(new java.io.StringReader(prefix))){
            r.beginObject();String obs="",pkg="";boolean hasEntries=false,hasTargets=false,malformed=false;int screenHeight=0;
            JSONObject targets=new JSONObject();JSONArray entries=new JSONArray(),actions=new JSONArray();
            while(r.hasNext()){
                String key=r.nextName();
                if(key.equals("observationId")){
                    obs=r.nextString();if(!wants(obs,null))return new JSONObject().put("irrelevant",true);
                    out.putBoolean("matching_observation_header_exposed",true);
                }else if(key.equals("packageName"))pkg=r.nextString();
                else if(key.equals("height"))screenHeight=r.nextInt();
                else if(key.equals("nodes")&&wants(obs,null)&&pkg.equals(expectedApp)){
                    r.beginArray();
                    while(r.hasNext()){
                        r.beginObject();String id="",safe=null;boolean clickable=false;
                        while(r.hasNext()){
                            String k=r.nextName();
                            if(k.equals("id"))id=r.nextString();
                            else if(wants(obs,id)&&k.equals("clickable"))clickable=r.nextBoolean();
                            else if(wants(obs,id)&&k.equals("resourceId")){
                                String res=r.nextString(),base=expectedApp+":id/";
                                if(res.startsWith(base)&&res.substring(base.length()).matches("[A-Za-z][A-Za-z0-9_]{0,23}"))safe=res.substring(base.length());
                            }else r.skipValue();
                        }
                        r.endObject();
                        if(wants(obs,id)){JSONObject d=new JSONObject().put("clickable",clickable);if(safe!=null)d.put("suffix",safe);targets.put(id,d);}
                    }r.endArray();
                }else if((key.equals("referenceEntries")||key.equals("actionTargets"))&&wants(obs,null)&&pkg.equals(expectedApp)){
                    boolean generic=key.equals("actionTargets");
                    if(generic)hasTargets=true;else hasEntries=true;
                    r.beginArray();
                    while(r.hasNext()){
                        JSONObject e=new JSONObject();r.beginObject();
                        while(r.hasNext()){
                            String k=r.nextName();
                            if(k.equals("tapNode")||k.equals("titleNode")||k.equals("labelNode"))e.put(k,r.nextString());
                            else if(k.equals("title")||k.equals("label"))e.put("quote",r.nextString().endsWith("的聊天记录"));
                            else if(k.equals("singleAction"))e.put("singleAction",r.nextBoolean());
                            else if(k.equals("evidenceNodes")){JSONArray ids=new JSONArray();r.beginArray();while(r.hasNext())ids.put(r.nextString());r.endArray();e.put(k,ids);}
                            else r.skipValue();
                        }r.endObject();
                        if(e.optString("tapNode").isEmpty()||e.optString(generic?"labelNode":"titleNode").isEmpty())malformed=true;
                        (generic?actions:entries).put(e);
                    }r.endArray();
                }else r.skipValue();
            }r.endObject();
            java.util.Set<String> tapped=new java.util.HashSet<>();for(Tap t:auditTaps)if(t.obs.equals(obs))tapped.add(t.node);
            return new JSONObject().put("observationId",obs).put("expectedApp",pkg.equals(expectedApp))
                    .put("targets",targets).put("entries",entries).put("field",hasEntries).put("malformed",malformed)
                    .put("actionField",hasTargets).put("actionTargets",actions)
                    .put("profiles",hasTargets?ReadTargetEvidence.fromWire(prefix,actions,tapped,screenHeight):new JSONObject());
        }catch(Exception incomplete){return null;}
    }
    private String activityLabel(AccessibilityNodeInfo n){
        for(String label:new String[]{DONE,STOP,FAIL})if(matches(n,label))return label;
        return null;
    }
    private AccessibilityNodeInfo tapContainer(AccessibilityNodeInfo row){
        AccessibilityNodeInfo n=row;
        while(n!=null&&harness.contentEquals(n.getPackageName())&&!n.isClickable())n=n.getParent();
        return n==null?row:n;
    }
    private AccessibilityNodeInfo genericRowFromEnd(int index){
        List<AccessibilityNodeInfo> a=rows();require(last(a,TURN)!=null&&last(a,"关闭")!=null);
        AccessibilityNodeInfo latest=last(a,"最新");if(latest!=null)tap(latest,"最新");
        for(int k=0;k<10&&scroll(rows(),true);k++){}
        LinkedHashSet<AccessibilityNodeInfo> seen=new LinkedHashSet<>();
        for(int page=0;page<16;page++){
            a=rows();
            for(int j=a.size()-1;j>=0;j--){
                AccessibilityNodeInfo n=a.get(j);
                if(activityLabel(n)!=null&&seen.add(tapContainer(n))&&seen.size()==index+1)return n;
            }
            if(!scroll(a,false))break;
        }
        return null;
    }
    private boolean noteJoin(JSONObject o) throws Exception{
        if(!o.optBoolean("expectedApp"))return false;
        for(Tap t:auditTaps){
            if(!t.obs.equals(o.optString("observationId")))continue;
            JSONObject d=o.getJSONObject("targets").optJSONObject(t.node);JSONArray entries=o.getJSONArray("entries");
            boolean parent=false,child=false,isForwarded=false;
            for(int i=0;i<entries.length();i++){
                JSONObject e=entries.getJSONObject(i);boolean direct=t.node.equals(e.optString("tapNode"));
                parent|=direct;child|=t.node.equals(e.optString("titleNode"));isForwarded|=direct&&e.optBoolean("quote");
            }
            JSONArray general=o.getJSONArray("actionTargets");boolean generalTap=false,generalChild=false,single=false,generalQuote=false;
            for(int i=0;i<general.length();i++){
                JSONObject e=general.getJSONObject(i);boolean direct=t.node.equals(e.optString("tapNode"));
                generalTap|=direct;generalChild|=t.node.equals(e.optString("labelNode"));
                single|=direct&&e.optBoolean("singleAction");generalQuote|=direct&&e.optBoolean("quote");
            }
            if(historicalSuffix!=null){
                if(d==null||!historicalSuffix.equals(d.optString("suffix"))||!generalTap||!single||t.coordinates||
                        !"navigate".equals(t.intent)||!STOP.equals(t.row)){
                    out.putBoolean("rejected_mismatched_prior_action_signature",true);continue;
                }
                out.putBoolean("previously_verified_stopped_action_signature",true);
            }
            // Keep the strongest negative evidence and prefer a proven parent over any later
            // unrelated tap. A title-node alias is reported separately, never as exact equality.
            if(out.getBoolean("matched_action_observation")&&!parent&&!child&&!generalTap&&!generalChild)continue;
            out.putBoolean("matched_action_observation",true);out.putBoolean("matched_same_observation_and_node",d!=null);
            out.putBoolean("observation_matches_expected_app",true);
            out.putBoolean("lease_inspection_app_exposed",false);
            out.putBoolean("native_view_class_exposed",false); // viewClass is not in observationJson()
            JSONObject profile=o.getJSONObject("profiles").optJSONObject(t.node);
            if(profile!=null)ReadTargetEvidence.publish(profile,out);
            out.putBoolean("reference_entries_field_present",o.getBoolean("field"));
            out.putBoolean("reference_entries_nonempty",entries.length()>0);
            out.putBoolean("reference_entries_malformed",o.getBoolean("malformed"));
            out.putBoolean("action_node_equals_tap_node",parent);
            out.putBoolean("action_node_equals_title_node",child);
            out.putBoolean("tap_node_is_forwarded_chat_entry",isForwarded);
            out.putBoolean("action_targets_field_present",o.getBoolean("actionField"));
            out.putBoolean("action_targets_nonempty",general.length()>0);
            out.putBoolean("action_node_equals_structured_tap",generalTap);
            out.putBoolean("action_node_equals_structured_label",generalChild);
            out.putBoolean("structured_tap_has_single_action",single);
            out.putBoolean("structured_tap_label_is_forwarded_chat",generalQuote);
            out.putBoolean("intent_navigate","navigate".equals(t.intent));out.putBoolean("intent_omitted",t.intent.isEmpty());
            out.putBoolean("intent_submit","submit".equals(t.intent));out.putBoolean("uses_coordinates",t.coordinates);
            out.putBoolean("action_row_completed",DONE.equals(t.row));out.putBoolean("action_row_stopped",STOP.equals(t.row));
            out.putBoolean("action_row_failed",FAIL.equals(t.row));out.putBoolean("action_receipt_verified",t.verifiedReceipt);
            out.remove("resource_suffix");if(d!=null&&d.has("suffix"))out.putString("resource_suffix",d.getString("suffix"));
            if(d!=null)out.putBoolean("action_node_clickable",d.optBoolean("clickable"));else out.remove("action_node_clickable");
            if(d!=null&&(generalTap||parent&&isForwarded)&&!t.coordinates&&!"submit".equals(t.intent)&&(DONE.equals(t.row)||historicalSuffix!=null))return true;
        }
        return false;
    }
    /** Optional retrospective pass over the already sent turn. No live action or QQ reader. */
    void inspectReferenceAction(){
        auditing=true;boolean turnOpen=false;String opened=null;
        try{
            openTurn();turnOpen=true;
            for(int index=0;index<24;index++){
                stage="locating_turn_tool";AccessibilityNodeInfo row=genericRowFromEnd(index);if(row==null)break;
                String label=activityLabel(row);showToolReader(row,label);opened=readerTitle(rows());require(opened!=null);
                boolean stop=false;
                if(opened.equals("android_action")){
                    stage="reading_action_input";JSONObject in=json(opened,"输入");
                    if(in!=null&&"tap".equals(in.optString("kind"))&&!in.optString("node").isEmpty()&&!in.optString("observationId").isEmpty()){
                        out.putBoolean("observed_node_tap_input",true);
                        JSONObject result=DONE.equals(label)?json(opened,"输出"):null;
                        auditTaps.add(new Tap(in,label,result));
                    }
                }else if(opened.equals("android_observe")&&!auditTaps.isEmpty()){
                    stage="matching_observation_receipt";
                    JSONObject observed=json(opened,"输出");
                    if(observed==null)out.putBoolean("observation_receipt_incomplete_or_filtered",true);
                    else if(!observed.optBoolean("irrelevant"))stop=noteJoin(observed);
                }
                stage="returning_to_verified_turn";closeReader(opened);opened=null;
                if(stop)break;
                List<AccessibilityNodeInfo> a=rows();
                if(last(a,DETAIL)!=null&&row.refresh()&&row.isVisibleToUser())tap(row,label);
            }
            out.putString("status",out.getBoolean("matched_action_observation")?"joined_action_to_observation":"no_complete_matching_receipt");
        }catch(Exception ignored){out.putString("inspection_limit",stage);}
        finally{
            try{if(opened!=null&&last(rows(),opened)!=null)closeReader(opened);
                if(turnOpen&&last(rows(),TURN)!=null&&last(rows(),"输入")==null)closeReader(TURN);
            }catch(Exception ignored){}
        }
    }
    private static String known(String s,String... choices){for(String choice:choices)if(choice.equals(s))return s;return s.isEmpty()?"omitted":"other";}
    void inspect(){
        boolean turnReader=false,actionReader=false,observationReader=false;
        try{
            // The normal approval preview is not an explanation of the policy gate.
            out.putString("approval_preview_category","not_visible");
            openTurn();turnReader=true;
            stage="stopped_call_not_available";AccessibilityNodeInfo stopped=openTool(STOP,0);
            stage="not_android_action_reader";
            List<AccessibilityNodeInfo> action=rows();String tool=last(action,"android_action")!=null?"android_action":
                    last(action,"android_sequence")!=null?"android_sequence":null;
            require(tool!=null);actionReader=true;
            stage="stopped_input_not_readable";JSONObject in=json(tool,"输入");require(in!=null);
            JSONObject one=in;JSONArray steps=in.optJSONArray("steps");
            if(steps!=null){out.putInt("sequence_steps",steps.length());one=steps.optJSONObject(0);require(one!=null);}
            out.putString("stopped_tool",tool);out.putString("action",known(one.optString("kind"),"tap","long_press","set_text","drag","launch","scroll","back","page_action","public_api"));
            String node=one.optString("node"),obs=in.optString("observationId");
            wantedObservation=obs;wantedNode=node;
            out.putBoolean("uses_node_ref",!node.isEmpty());out.putBoolean("uses_coordinates",one.has("x")&&one.has("y"));
            out.putString("intent",known(one.optString("intent"),"navigate","submit"));
            out.putBoolean("stopped_input_classified",true);out.putString("status","stopped_input_classified");
            stage="closing_classified_action_reader";closeReader(tool);actionReader=false;if(node.isEmpty()||obs.isEmpty())return;
            // Collapse only the inspected row, keeping the adjacent completed row identifiable.
            List<AccessibilityNodeInfo> a=rows();
            stage="folding_classified_action_row";
            if(last(a,DETAIL)!=null&&stopped.refresh()&&stopped.isVisibleToUser())tap(stopped,STOP);
            JSONObject observed=null;
            for(int i=0;i<12;i++){
                stage="earlier_tool_not_available";AccessibilityNodeInfo prior=openTool(DONE,i);
                String earlier=readerTitle(rows());require(earlier!=null);
                if("android_observe".equals(earlier)){
                    observationReader=true;stage="reading_prior_observation";
                    observed=json("android_observe","输出");
                    if(observed!=null&&obs.equals(observed.optString("observationId"))&&"com.tencent.mobileqq".equals(observed.optString("packageName")))break;
                    observed=null;
                }
                stage="returning_from_other_receipt";closeReader(earlier);observationReader=false;
                a=rows();if(last(a,DETAIL)!=null&&prior.refresh()&&prior.isVisibleToUser())tap(prior,DONE);
            }
            if(observed==null)return;
            JSONArray nodes=observed.optJSONArray("nodes");if(nodes==null)return;JSONObject match=null;
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&node.equals(n.optString("id"))){if(match!=null)return;match=n;}}
            if(match==null)return;
            out.putBoolean("matched_same_observation_and_node",true);
            String t=match.optString("text"),d=match.optString("description");
            out.putBoolean("target_has_text",!t.trim().isEmpty());out.putBoolean("target_has_description",!d.trim().isEmpty());
            out.putBoolean("target_label_is_forwarded_chat",t.endsWith("的聊天记录")||d.endsWith("的聊天记录"));
            out.putBoolean("target_clickable",match.optBoolean("clickable",false));
            String resource=match.optString("resourceId");String prefix="com.tencent.mobileqq:id/";
            if(resource.startsWith(prefix)&&resource.substring(prefix.length()).matches("[A-Za-z][A-Za-z0-9]{0,23}"))
                out.putString("resource_suffix",resource.substring(prefix.length()));
        }catch(Exception ignored){out.putString("inspection_limit",stage);}
        finally{
            // Only close trace views this test opened with a verified title and dialog shape.
            try{
                if(observationReader)closeReader("android_observe");
                if(actionReader){for(String t:new String[]{"android_action","android_sequence"})if(last(rows(),t)!=null){closeReader(t);break;}}
                if(turnReader&&last(rows(),TURN)!=null&&last(rows(),"输入")==null)closeReader(TURN);
            }catch(Exception ignored){/* leave the current Harness view untouched */}
        }
    }
}
