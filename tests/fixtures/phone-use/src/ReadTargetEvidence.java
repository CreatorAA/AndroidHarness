package com.androidharness.fixture.phoneuse;

import android.graphics.Rect;
import android.os.Bundle;
import android.util.JsonReader;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only diagnostics for the 4.0.11 managed read-target checks. Unlike the product this
 * helper neither authorizes actions nor evaluates a lease. Only classifications leave the device. */
final class ReadTargetEvidence {
    private static Pattern pat(String s){return Pattern.compile(s,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);}
    private static final Pattern TITLE=pat("(?:[\\p{L}\\p{N}\\p{So}\\p{M} ·•、_.…（）()\\-]{1,64}的聊天记录|聊天记录|群聊记录|引用原文|引用消息|quoted message|conversation history)");
    private static final Pattern AFFORDANCE=pat("(?:(?:查看|浏览|阅读|打开|展开)?(?:详情|详细信息|介绍|说明|正文|原文|引用|上下文)|(?:view|read|show|open) (?:details|description|original)|details)");
    private static final Pattern REFNAV=pat("(?:查看(?:引用|回复)(?:[：:]\\s*)?)?(?:群聊的聊天记录|聊天记录|引用原文|引用消息)|查看原文|查看上下文|展开引用|view (?:quoted message|original message|conversation history)");
    private static final Pattern PROTECTED=pat("支付|付款|转账|授权|人脸识别|验证身份|验证码|密码|通行密钥|登录|登入|注册|权限|pay(?:ment)?\\b|checkout|transfer funds|authorize|password|passkey|verification code|sign[ -]?in|log[ -]?in|grant permission");
    private static final Pattern COMMIT=pat("发送|提交|发布|删除|下单|购买|分享|转发|拨打|保存|清空|移除|卸载|安装|同意|确认|退出登录|\\b(send|submit|publish|delete|purchase|buy|share|forward|call|save|erase|remove|uninstall|install|agree|accept|confirm)\\b");
    private static final String URL="[a-z][a-z0-9+.-]*://|\\b(?:mailto|intent|tel|sms|javascript|data):|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b";
    private static final Pattern URL_TOKEN=pat(URL),UNSAFE=pat(URL+"|链接|网页|浏览器|小程序|外部应用|打开应用|下载|订阅|运行|启用|禁用|报名|确认预约|立即预订|加入购物车|收藏|邀请|加好友|点赞|\\b(?:link|download|browser|webview|checkout|subscribe|book now|run|invite|favou?rite|follow|bookmark)\\b");
    static boolean title(String field){return TITLE.matcher(field.trim()).matches();}
    private static boolean risk(String s){return PROTECTED.matcher(s).find()||COMMIT.matcher(s).find()||UNSAFE.matcher(s).find();}
    private static String val(CharSequence value){return value==null?"":value.toString().trim();}
    static final class Node{
        String id="",t="",d="",rs="";boolean click,edit;Rect rect;
        Node(){}
        Node(AccessibilityNodeInfo n){t=val(n.getText());d=val(n.getContentDescription());rs=val(n.getViewIdResourceName());click=n.isClickable();edit=n.isEditable();rect=new Rect();n.getBoundsInScreen(rect);}
        String resource(){return rs.substring(rs.lastIndexOf('/')+1).replace('_',' ').replaceAll("(?<=[a-z])(?=[A-Z])"," ");}
        String labels(){return t+" "+d;}
        boolean ref(){return title(t)||title(d);}
    }
    private static void flag(JSONObject o,String k,boolean v)throws Exception{o.put(k,o.optBoolean(k)||v);}
    private static void risks(JSONObject o,String pre,Node n)throws Exception{
        String l=n.labels(),r=n.resource(),all=l+" "+r;
        flag(o,pre+"_risky",risk(all));flag(o,pre+"_text_or_desc_risky",risk(l));
        flag(o,pre+"_resource_risky",risk(r));flag(o,pre+"_label_url_or_domain",URL_TOKEN.matcher(l).find());
        flag(o,pre+"_resource_url_or_domain",URL_TOKEN.matcher(r).find());flag(o,pre+"_protected",PROTECTED.matcher(all).find());
        flag(o,pre+"_commitment",COMMIT.matcher(all).find());flag(o,pre+"_unsafe_read",UNSAFE.matcher(all).find());
    }
    private static String compact(String s){return s.replaceAll("[\\s\\p{Z},，、;；|]+","");}
    // A strictly ordered concatenation of at least two distinct passive fields after removing
    // separators; a containment or a vague semantic similarity is not an aggregation match.
    private static boolean aggregate(String self,List<String> passive){
        if(self.isEmpty())return false;String rest=compact(self);Set<String> used=new HashSet<>();
        for(String s:passive){String token=compact(s);if(!token.isEmpty()&&rest.startsWith(token)){rest=rest.substring(token.length());used.add(token);}}
        return rest.isEmpty()&&used.size()>=2;
    }
    static JSONObject classify(Node parent,List<Node> evidence,int height)throws Exception{
        JSONObject o=new JSONObject();LinkedHashSet<String> distinct=new LinkedHashSet<>(),children=new LinkedHashSet<>();
        ArrayList<String> passive=new ArrayList<>();Set<String> distinctTitles=new HashSet<>();
        int referenceNodes=0,passiveCount=0;boolean inside=parent.rect!=null,rects=parent.rect!=null,extra=false,editable=parent.edit,passiveRisk=false,ownerIncluded=false;
        for(Node n:evidence){
            for(String s:new String[]{n.t,n.d})if(!s.isEmpty()){distinct.add(s);if(title(s))distinctTitles.add(s);}
            if(n.ref())referenceNodes++;
            if(n==parent){ownerIncluded=true;continue;}
            editable|=n.edit;extra|=n.click;
            for(String s:new String[]{n.t,n.d})if(!s.isEmpty()){children.add(s);if(!n.click&&!n.edit)passive.add(s);}
            if(!n.click&&!n.edit){passiveCount++;risks(o,"passive_child",n);passiveRisk|=risk(n.labels()+" "+n.resource());}
            if(n.rect==null||parent.rect==null){rects=false;inside=false;}
            else inside &= n.rect.left>=parent.rect.left&&n.rect.right<=parent.rect.right&&n.rect.top>=parent.rect.top&&n.rect.bottom<=parent.rect.bottom;
        }
        boolean ownEmpty=parent.t.isEmpty()&&parent.d.isEmpty(),exact=!ownEmpty,aggregateOnly=!ownEmpty,hasAggregation=false,conflict=false;
        for(String s:new String[]{parent.t,parent.d})if(!s.isEmpty()){
            boolean eq=children.contains(s),joined=aggregate(s,passive);exact&=eq;aggregateOnly&=eq||joined;hasAggregation|=joined;
            conflict|=!eq&&!AFFORDANCE.matcher(s).matches()&&!REFNAV.matcher(s).matches();
        }
        risks(o,"parent",parent);
        o.put("parent_text_empty",parent.t.isEmpty());o.put("parent_description_empty",parent.d.isEmpty());
        o.put("parent_text_is_child_field",!parent.t.isEmpty()&&children.contains(parent.t));
        o.put("parent_description_is_child_field",!parent.d.isEmpty()&&children.contains(parent.d));
        o.put("parent_own_fields_exact_children",exact);
        o.put("parent_fields_only_child_labels_or_aggregate",aggregateOnly);o.put("parent_contains_ordered_passive_aggregate",hasAggregation);
        o.put("parent_own_label_conflicts_with_child_rule",conflict);
        o.put("parent_matches_reference_title",parent.ref());o.put("source_evidence_nodes",evidence.size());
        o.put("parent_in_evidence_nodes",ownerIncluded);o.put("passive_child_nodes",passiveCount);
        o.put("passive_child_risky",passiveRisk);o.put("evidence_distinct_fields",distinct.size());
        o.put("evidence_full_reference_title_nodes",referenceNodes);o.put("evidence_distinct_full_reference_titles",distinctTitles.size());
        o.put("evidence_has_complete_reference_title",referenceNodes>0);o.put("evidence_has_other_clickable",extra);
        o.put("evidence_has_editable",editable);o.put("evidence_bounds_exposed",rects);
        if(rects)o.put("all_evidence_inside_tap_bounds",inside);
        if(parent.rect!=null&&height>0)o.put("card_height_at_most_half",parent.rect.height()<=height/2);
        return o;
    }
    static void publish(JSONObject profile,Bundle out)throws Exception{
        for(Iterator<String> it=profile.keys();it.hasNext();){String k=it.next();Object v=profile.get(k);
            if(v instanceof Boolean)out.putBoolean(k,(Boolean)v);else if(v instanceof Integer)out.putInt(k,(Integer)v);}
    }
    /** Decode only a complete, bounded, matching UI receipt. Other rows need only the yes/no
     * full-title marker to count owners: their text, identifiers and geometry are not retained
     * in the returned object. No per-node text, IDs, coordinates or exception messages leave. */
    static JSONObject fromWire(String blob,JSONArray actions,Set<String> wantedTap,int height)throws Exception{
        Set<String> allEvidence=new HashSet<>(),wanted=new HashSet<>(wantedTap);
        for(int i=0;i<actions.length();i++){JSONObject a=actions.getJSONObject(i);JSONArray ids=a.optJSONArray("evidenceNodes");if(ids==null)continue;
            for(int j=0;j<ids.length();j++){String id=ids.getString(j);allEvidence.add(id);if(wantedTap.contains(a.optString("tapNode")))wanted.add(id);}}
        Map<String,Node> match=new HashMap<>();Set<String> seen=new HashSet<>(),ref=new HashSet<>();
        try(JsonReader r=new JsonReader(new StringReader(blob))){
            r.beginObject();while(r.hasNext()){
                if(!r.nextName().equals("nodes")){r.skipValue();continue;}
                r.beginArray();while(r.hasNext()){
                    r.beginObject();Node n=new Node();boolean keep=false,detect=false;
                    while(r.hasNext()){
                        String k=r.nextName();
                        if(k.equals("id")){n.id=r.nextString();keep=wanted.contains(n.id);detect=allEvidence.contains(n.id);}
                        else if((keep||detect)&&(k.equals("text")||k.equals("description"))){
                            String s=r.nextString().trim();if(title(s))ref.add(n.id);if(keep){if(k.equals("text"))n.t=s;else n.d=s;}
                        }else if(keep&&k.equals("resourceId"))n.rs=r.nextString();
                        else if(keep&&k.equals("clickable"))n.click=r.nextBoolean();
                        else if(keep&&k.equals("editable"))n.edit=r.nextBoolean();
                        else if(keep&&k.equals("screenBounds")){r.beginArray();n.rect=new Rect(r.nextInt(),r.nextInt(),r.nextInt(),r.nextInt());r.endArray();}
                        else r.skipValue();
                    }r.endObject();if(keep)match.put(n.id,n);if(detect)seen.add(n.id);
                }r.endArray();
            }r.endObject();
        }
        Set<String> owners=new HashSet<>();boolean ownerCountComplete=true;
        for(int i=0;i<actions.length();i++){JSONObject a=actions.getJSONObject(i);JSONArray ids=a.optJSONArray("evidenceNodes");
            if(ids==null){ownerCountComplete=false;continue;}for(int j=0;j<ids.length();j++){String id=ids.getString(j);if(!seen.contains(id))ownerCountComplete=false;if(ref.contains(id))owners.add(a.optString("tapNode"));}}
        JSONObject profiles=new JSONObject();
        for(int i=0;i<actions.length();i++){JSONObject a=actions.getJSONObject(i);String id=a.optString("tapNode");if(!wantedTap.contains(id))continue;
            Node p=match.get(id);JSONArray ids=a.optJSONArray("evidenceNodes");JSONObject o=new JSONObject();
            o.put("evidence_node_refs_exposed",ids!=null);o.put("reference_owner_count_complete",ownerCountComplete);
            if(ownerCountComplete)o.put("distinct_tap_targets_with_full_reference_title",owners.size());
            List<Node> picked=new ArrayList<>();if(ids!=null)for(int j=0;j<ids.length();j++){Node n=match.get(ids.getString(j));if(n!=null)picked.add(n);}
            boolean complete=p!=null&&ids!=null&&picked.size()==ids.length();o.put("all_target_evidence_joined",complete);
            if(complete){JSONObject b=classify(p,picked,height);for(Iterator<String> ks=b.keys();ks.hasNext();){String k=ks.next();o.put(k,b.get(k));}}
            profiles.put(id,o);
        }
        return profiles;
    }
}
