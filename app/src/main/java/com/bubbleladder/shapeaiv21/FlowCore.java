package com.bubbleladder.shapeaiv21;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public final class FlowCore {
    private FlowCore(){}

    public static final String API="https://api.bepick.io/game/bubble_ladder3";
    public static final String PREF="bubble_shape_ai_v21";
    public static final String ACTION_UPDATED="com.bubbleladder.shapeaiv21.FLOW_UPDATED";
    public static final int WINDOW=480;
    public static final double PICK_THRESHOLD=0.70;

    public static final String K_HISTORY="history", K_RECORDS="records",
            K_PENDING_IDX="pending_idx", K_PENDING_DIM="pending_dim", K_PENDING_PICK="pending_pick",
            K_PENDING_CONF="pending_conf", K_PENDING_STAKE="pending_stake", K_PENDING_ODDS="pending_odds",
            K_LIVE_TOTAL="live_total", K_LIVE_SUCCESS="live_success", K_LIVE_PROFIT="live_profit",
            K_BASE_STAKE="base_stake", K_ODDS="odds", K_AUTO="auto_enabled",
            K_LAST_PICK="last_pick", K_LAST_CONF="last_conf", K_LAST_SYNC="last_sync";

    public static final String[] COMBO={"","좌3짝","좌4홀","우3홀","우4짝"};
    public static final String[] DIM={"좌/우","사다리수","홀/짝"};
    // +1 = 좌 / 3줄 / 홀, -1 = 우 / 4줄 / 짝
    private static final int[][] VEC={{0,0,0},{+1,+1,-1},{+1,-1,+1},{-1,+1,+1},{-1,-1,-1}};

    public static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    public static final class Result { public long idx; public String date; public int round,combo; }

    public static final class ShapeStat {
        public int length,exactMatches,nearMatches,pick;
        public double sameWeight,flipWeight,pPlus,confidence,effectiveMatches;
        public boolean ready;
        public String shape="-", tendency="-";
        public String label(String dim){
            String dir=pick==0?"중립":sideLabel(dim,pick);
            return "모양 "+shape+" · 완전 "+exactMatches+" / 유사 "+nearMatches+
                    " · "+tendency+" · "+dir+" "+pct(confidence);
        }
    }

    public static final class DimensionStat {
        public String name;
        public ShapeStat main3,confirm4,assist5;
        public int pick;
        public double confidence;
        public boolean qualified;
        public String verdict;
    }

    public static final class Backtest {
        public int globalN,globalHit;
        public int[] dimN=new int[3],dimHit=new int[3];
    }

    public static final class Analysis {
        public DimensionStat[] dims;
        public int bestDim=-1,bestPick=0,count;
        public double bestConfidence;
        public String bestLabel="대기",date="",windowRange="",suffix="";
        public Backtest backtest;
    }

    public static final class SyncResult {
        public boolean newRoundResolved;
        public Analysis analysis;
        public List<Result> history;
    }

    public static List<Result> fetch() throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","BubbleShapeAI/2.1");
        int code=c.getResponseCode(); if(code<200||code>=300)throw new Exception("API HTTP "+code);
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
        StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null)sb.append(line); br.close(); c.disconnect();
        JSONObject root=new JSONObject(sb.toString()); JSONArray arr=root.optJSONArray("data");
        if(arr==null)throw new Exception("API data 없음");
        List<Result> out=new ArrayList<>();
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i); if(o==null)continue;
            int combo=o.optInt("fd4",0); long idx=o.optLong("idx",0);
            if(idx<=0||combo<1||combo>4)continue;
            Result r=new Result(); r.idx=idx; r.date=o.optString("date",""); r.round=o.optInt("round",0); r.combo=combo; out.add(r);
        }
        out.sort((a,b)->Long.compare(b.idx,a.idx)); if(out.isEmpty())throw new Exception("결과 없음"); return out;
    }

    public static List<Result> load(Context c){
        List<Result> out=new ArrayList<>(); String raw=prefs(c).getString(K_HISTORY,""); if(raw==null||raw.isEmpty())return out;
        try{
            JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){
                JSONObject j=a.optJSONObject(i); if(j==null)continue;
                Result r=new Result(); r.idx=j.optLong("i"); r.date=j.optString("d",""); r.round=j.optInt("r",0); r.combo=j.optInt("c",0);
                if(r.idx>0&&r.combo>=1&&r.combo<=4)out.add(r);
            }
        }catch(Exception ignored){}
        out.sort((a,b)->Long.compare(b.idx,a.idx)); if(out.size()>WINDOW)out=new ArrayList<>(out.subList(0,WINDOW)); return out;
    }

    public static void save(Context c,List<Result> list){
        try{
            JSONArray a=new JSONArray(); int n=Math.min(WINDOW,list.size());
            for(int i=0;i<n;i++){ Result r=list.get(i); JSONObject o=new JSONObject(); o.put("i",r.idx);o.put("d",r.date);o.put("r",r.round);o.put("c",r.combo);a.put(o); }
            prefs(c).edit().putString(K_HISTORY,a.toString()).apply();
        }catch(Exception ignored){}
    }

    public static SyncResult sync(Context c)throws Exception{
        List<Result> before=load(c); long latestBefore=before.isEmpty()?-1:before.get(0).idx; List<Result> api=fetch();
        TreeMap<Long,Result> map=new TreeMap<>(Collections.reverseOrder()); for(Result r:before)map.put(r.idx,r); for(Result r:api)map.put(r.idx,r);
        List<Result> merged=new ArrayList<>(map.values()); if(merged.size()>WINDOW)merged=new ArrayList<>(merged.subList(0,WINDOW));
        boolean resolved=resolvePending(c,merged); save(c,merged); Analysis a=analyze(merged); savePending(c,merged,a);
        prefs(c).edit().putLong(K_LAST_SYNC,System.currentTimeMillis()).apply();
        SyncResult sr=new SyncResult(); sr.newRoundResolved=resolved||(!merged.isEmpty()&&merged.get(0).idx!=latestBefore); sr.analysis=a; sr.history=merged; return sr;
    }

    public static Analysis analyze(List<Result> desc){
        if(desc==null||desc.isEmpty())return null;
        List<Result> all=chronoAsc(desc); if(all.size()>WINDOW)all=new ArrayList<>(all.subList(all.size()-WINDOW,all.size()));
        Analysis a=decision(all,0,all.size());
        a.count=all.size(); a.date=dayKey(all.get(all.size()-1).date); a.windowRange=rangeLabel(all,0,all.size()); a.suffix=suffixText(all,all.size(),8); a.backtest=backtest(all); return a;
    }

    private static Analysis decision(List<Result> all,int start,int end){
        Analysis a=new Analysis(); a.dims=new DimensionStat[3];
        for(int dim=0;dim<3;dim++)a.dims[dim]=dimensionDecision(all,start,end,dim);
        for(int dim=0;dim<3;dim++){
            DimensionStat ds=a.dims[dim];
            if(ds.qualified && (a.bestDim<0 || ds.confidence>a.bestConfidence)){
                a.bestDim=dim; a.bestPick=ds.pick; a.bestConfidence=ds.confidence;
            }
        }
        if(a.bestDim>=0)a.bestLabel=DIM[a.bestDim]+" · "+sideLabel(DIM[a.bestDim],a.bestPick);
        else { a.bestLabel="대기 · 70% 이상 없음"; a.bestPick=0; a.bestConfidence=0; }
        return a;
    }

    private static DimensionStat dimensionDecision(List<Result> all,int start,int end,int dim){
        DimensionStat ds=new DimensionStat(); ds.name=DIM[dim];
        ds.main3=shapeStat(all,start,end,3,dim,10);
        ds.confirm4=shapeStat(all,start,end,4,dim,5);
        ds.assist5=shapeStat(all,start,end,5,dim,3);
        if(!ds.main3.ready || ds.main3.pick==0){ ds.pick=0; ds.confidence=0.5; ds.qualified=false; ds.verdict="3칸 메인 표본 부족"; return ds; }
        ds.pick=ds.main3.pick;
        double m=supportFor(ds.main3,ds.pick);
        double c=ds.confirm4.ready?supportFor(ds.confirm4,ds.pick):0.5;
        double s=ds.assist5.ready?supportFor(ds.assist5,ds.pick):0.5;
        ds.confidence=0.60*m+0.25*c+0.15*s;
        ds.qualified=ds.confidence>=PICK_THRESHOLD;
        int agree=1+(ds.confirm4.ready&&ds.confirm4.pick==ds.pick?1:0)+(ds.assist5.ready&&ds.assist5.pick==ds.pick?1:0);
        ds.verdict=(agree==3?"3·4·5 모양 합의":agree==2?"메인 + 보조 1개 합의":"3칸 메인 단독")+" · "+pct(ds.confidence);
        return ds;
    }

    private static double supportFor(ShapeStat s,int pick){ return pick>0?s.pPlus:1.0-s.pPlus; }

    // 결과값 자체가 아니라 '같은 값 유지 / 반전' 전이 모양을 비교한다.
    // 좌↔우처럼 값 전체가 반전되어도 전이 모양이 같으므로 같은 패턴군으로 자동 학습된다.
    private static ShapeStat shapeStat(List<Result>a,int start,int end,int len,int dim,int minEffective){
        ShapeStat st=new ShapeStat(); st.length=len;
        if(end-start<=len){ st.confidence=0.5; return st; }
        int[] cur=new int[len]; for(int j=0;j<len;j++)cur[j]=VEC[a.get(end-len+j).combo][dim];
        int[] curRel=relations(cur); st.shape=shapeLabel(curRel);
        double same=0,flip=0; int exact=0,near=0;
        for(int next=start+len;next<end;next++){
            int[] old=new int[len]; for(int j=0;j<len;j++)old[j]=VEC[a.get(next-len+j).combo][dim];
            int[] rel=relations(old); int hd=hamming(curRel,rel); double w;
            if(hd==0){w=1.0;exact++;}
            else if(hd==1){w=0.35;near++;}
            else continue;
            int last=old[len-1], n=VEC[a.get(next).combo][dim]; if(n==last)same+=w; else flip+=w;
        }
        st.exactMatches=exact; st.nearMatches=near; st.sameWeight=same; st.flipWeight=flip; st.effectiveMatches=same+flip;
        double pSame=(same+2.0)/(same+flip+4.0); // 저표본은 50% 쪽으로 축소
        int last=cur[len-1]; st.pPlus=last>0?pSame:1.0-pSame; st.pick=st.pPlus>0.5?+1:st.pPlus<0.5?-1:0;
        st.confidence=Math.max(st.pPlus,1.0-st.pPlus); st.ready=st.effectiveMatches>=minEffective;
        st.tendency=pSame>=0.5?"연장 "+pct(pSame):"꺾임 "+pct(1.0-pSame);
        if(!st.ready)st.confidence=0.5+(st.confidence-0.5)*Math.min(1.0,st.effectiveMatches/minEffective);
        return st;
    }

    private static int[] relations(int[] v){ int[] r=new int[v.length-1]; for(int i=1;i<v.length;i++)r[i]=v[i]==v[i-1]?+1:-1; return r; }
    private static int hamming(int[]a,int[]b){ int n=0; for(int i=0;i<a.length;i++)if(a[i]!=b[i])n++; return n; }
    private static String shapeLabel(int[] rel){ StringBuilder sb=new StringBuilder(); for(int i=0;i<rel.length;i++){ if(i>0)sb.append("→"); sb.append(rel[i]>0?"유지":"반전"); } return sb.toString(); }

    private static Backtest backtest(List<Result> all){
        Backtest b=new Backtest();
        for(int t=20;t<all.size();t++){
            int start=Math.max(0,t-WINDOW); Analysis a=decision(all,start,t); int actualCombo=all.get(t).combo;
            for(int dim=0;dim<3;dim++){
                DimensionStat ds=a.dims[dim]; if(!ds.qualified)continue; b.dimN[dim]++; if(ds.pick==VEC[actualCombo][dim])b.dimHit[dim]++;
            }
            if(a.bestDim>=0){ b.globalN++; if(a.bestPick==VEC[actualCombo][a.bestDim])b.globalHit++; }
        }
        return b;
    }

    private static void savePending(Context c,List<Result>d,Analysis a){
        if(d.isEmpty()||a==null||a.bestDim<0||a.bestPick==0)return;
        SharedPreferences sp=prefs(c); long next=nextIdx(d.get(0)); long existing=sp.getLong(K_PENDING_IDX,-1); if(existing==next)return;
        int stake=Math.max(5000,sp.getInt(K_BASE_STAKE,5000)); double odds=Math.max(1.01,sp.getFloat(K_ODDS,1.95f));
        String pick=a.bestLabel+" · "+pct(a.bestConfidence);
        sp.edit().putLong(K_PENDING_IDX,next).putInt(K_PENDING_DIM,a.bestDim).putInt(K_PENDING_PICK,a.bestPick)
                .putFloat(K_PENDING_CONF,(float)a.bestConfidence).putInt(K_PENDING_STAKE,stake).putFloat(K_PENDING_ODDS,(float)odds)
                .putString(K_LAST_PICK,pick).putFloat(K_LAST_CONF,(float)a.bestConfidence).apply();
    }

    private static boolean resolvePending(Context c,List<Result>d){
        SharedPreferences sp=prefs(c); long idx=sp.getLong(K_PENDING_IDX,-1); int dim=sp.getInt(K_PENDING_DIM,-1),pick=sp.getInt(K_PENDING_PICK,0);
        if(idx<=0||dim<0||dim>2||pick==0)return false;
        Result actual=null; for(Result r:d)if(r.idx==idx){actual=r;break;} if(actual==null)return false;
        boolean ok=VEC[actual.combo][dim]==pick; int st=sp.getInt(K_PENDING_STAKE,5000); double o=sp.getFloat(K_PENDING_ODDS,1.95f); double pnl=ok?st*(o-1.0):-st;
        int n=sp.getInt(K_LIVE_TOTAL,0)+1,hit=sp.getInt(K_LIVE_SUCCESS,0)+(ok?1:0); double old=Double.longBitsToDouble(sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
        appendRecord(c,idx,dim,pick,sp.getFloat(K_PENDING_CONF,0.5f),actual.combo,ok,pnl);
        sp.edit().putInt(K_LIVE_TOTAL,n).putInt(K_LIVE_SUCCESS,hit).putLong(K_LIVE_PROFIT,Double.doubleToLongBits(old+pnl))
                .remove(K_PENDING_IDX).remove(K_PENDING_DIM).remove(K_PENDING_PICK).remove(K_PENDING_CONF).remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).apply();
        return true;
    }

    private static void appendRecord(Context c,long idx,int dim,int pick,double conf,int actual,boolean ok,double pnl){
        try{
            SharedPreferences sp=prefs(c); JSONArray a=new JSONArray(sp.getString(K_RECORDS,"[]")); JSONObject o=new JSONObject();
            o.put("idx",idx);o.put("dim",dim);o.put("pick",pick);o.put("conf",conf);o.put("actual",actual);o.put("ok",ok);o.put("pnl",pnl);a.put(o);
            JSONArray out=new JSONArray();for(int i=Math.max(0,a.length()-1500);i<a.length();i++)out.put(a.get(i)); sp.edit().putString(K_RECORDS,out.toString()).apply();
        }catch(Exception ignored){}
    }

    public static void resetPerformance(Context c){
        prefs(c).edit().remove(K_RECORDS).remove(K_PENDING_IDX).remove(K_PENDING_DIM).remove(K_PENDING_PICK).remove(K_PENDING_CONF)
                .remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).remove(K_LIVE_TOTAL).remove(K_LIVE_SUCCESS).remove(K_LIVE_PROFIT).apply();
    }
    public static void resetAll(Context c){ prefs(c).edit().clear().putBoolean(K_AUTO,false).putInt(K_BASE_STAKE,5000).putFloat(K_ODDS,1.95f).apply(); }

    public static JSONObject backup(Context c)throws Exception{
        SharedPreferences sp=prefs(c); JSONObject root=new JSONObject(); root.put("format","BubbleShapeAIV21Backup");
        root.put("history",new JSONArray(sp.getString(K_HISTORY,"[]"))); root.put("records",new JSONArray(sp.getString(K_RECORDS,"[]")));
        JSONObject st=new JSONObject(); st.put("liveTotal",sp.getInt(K_LIVE_TOTAL,0));st.put("liveHit",sp.getInt(K_LIVE_SUCCESS,0));st.put("liveProfit",sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
        st.put("stake",sp.getInt(K_BASE_STAKE,5000));st.put("odds",sp.getFloat(K_ODDS,1.95f));st.put("auto",sp.getBoolean(K_AUTO,false));root.put("state",st); return root;
    }
    public static void restore(Context c,JSONObject root)throws Exception{
        SharedPreferences.Editor ed=prefs(c).edit(); JSONArray src=root.optJSONArray("history");
        if(src!=null){ TreeMap<Long,JSONObject> map=new TreeMap<>(Collections.reverseOrder()); for(int i=0;i<src.length();i++){JSONObject o=src.optJSONObject(i);if(o!=null){long idx=o.optLong("i",o.optLong("idx",0));if(idx>0)map.put(idx,o);}}
            JSONArray cut=new JSONArray();int n=0;for(JSONObject o:map.values()){if(n++>=WINDOW)break;cut.put(o);}ed.putString(K_HISTORY,cut.toString()); }
        if(root.has("records"))ed.putString(K_RECORDS,root.getJSONArray("records").toString()); JSONObject st=root.optJSONObject("state");
        if(st!=null){ed.putInt(K_LIVE_TOTAL,st.optInt("liveTotal",0));ed.putInt(K_LIVE_SUCCESS,st.optInt("liveHit",0));ed.putLong(K_LIVE_PROFIT,st.optLong("liveProfit",Double.doubleToLongBits(0)));ed.putInt(K_BASE_STAKE,Math.max(5000,st.optInt("stake",5000)));ed.putFloat(K_ODDS,(float)st.optDouble("odds",1.95));ed.putBoolean(K_AUTO,false);}
        ed.apply();
    }

    private static List<Result> chronoAsc(List<Result>desc){List<Result>copy=new ArrayList<>(desc);copy.sort(Comparator.comparingLong(x->x.idx));return copy;}
    public static List<Result> recentDesc(List<Result>desc,int limit){List<Result>copy=new ArrayList<>(desc);copy.sort((a,b)->Long.compare(b.idx,a.idx));return copy.size()>limit?new ArrayList<>(copy.subList(0,limit)):copy;}
    private static String dayKey(String s){String digits=String.valueOf(s==null?"":s).replaceAll("\\D","");return digits.length()>=8?digits.substring(0,8):String.valueOf(s==null?"":s);}
    private static String suffixText(List<Result>a,int end,int max){int from=Math.max(0,end-max);StringBuilder sb=new StringBuilder();for(int i=from;i<end;i++){if(sb.length()>0)sb.append(" → ");sb.append(COMBO[a.get(i).combo]);}return sb.toString();}
    private static String rangeLabel(List<Result>a,int start,int end){if(end<=start)return "-";Result first=a.get(start),last=a.get(end-1);return first.date+" "+first.round+"회 → "+last.date+" "+last.round+"회";}
    public static String sideLabel(String dim,int v){if("좌/우".equals(dim))return v>0?"좌":"우";if("사다리수".equals(dim))return v>0?"3줄":"4줄";return v>0?"홀":"짝";}
    public static long nextIdx(Result r){try{String dk=dayKey(r.date);if(r.round<480)return Long.parseLong(dk.substring(2,8)+String.format(Locale.US,"%04d",r.round+1));SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(dk));c.add(Calendar.DAY_OF_MONTH,1);String d=f.format(c.getTime());return Long.parseLong(d.substring(2,8)+"0001");}catch(Exception e){return r.idx+1;}}
    public static long millisToNextDraw(){long interval=180000L,now=System.currentTimeMillis();long mod=Math.floorMod(now,interval);long left=interval-mod;return left==0?interval:left;}
    public static String countdownText(){long s=(millisToNextDraw()+999)/1000;return String.format(Locale.KOREA,"%02d:%02d",s/60,s%60);}
    public static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100);}
    public static String money(double v){return String.format(Locale.KOREA,"%,.0f원",v);}
    public static String signed(double v){return (v>=0?"+":"")+money(v);}
    public static String liveRate(Context c){SharedPreferences sp=prefs(c);int n=sp.getInt(K_LIVE_TOTAL,0),h=sp.getInt(K_LIVE_SUCCESS,0);return n==0?"-":h+"/"+n+" ("+pct((double)h/n)+")";}
}
