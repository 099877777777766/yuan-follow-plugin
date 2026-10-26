package com.local.yuanatlas;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;
import java.util.zip.InflaterInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** 仅战斗辅助读取 YRAF1：先认证解密，再读数据；OCR 结果不能直接驱动手势。 */
final class RecorderFollowTable {
    // App 公开协议参数，与导出端一致；新跟打码不依赖用户或设备密钥。
    private static final String APP_KEY="6a3a44e1-f262-4c12-9492-3c276f7d0a81";
    private static final class MissingKey extends IllegalArgumentException {MissingKey(){super("旧版跟打码需要重新导出为通用码");}}
    static final String[] COLORS={"none","yellow","red","green","blue","gray"};
    static final int[] FILLS={0xffedf3f1,0xffffeb99,0xfff69b9d,0xffb3ef9f,0xff9db8fa,0xffc7c7c7};
    static final class Step {
        final int row,column,order,entry;final String action,color;
        // 条件动作绑定原出手位置；固定行动位的偏移为 0，暂停游标沿用源步骤身份。
        final Step original;final int branchOffset;long rowId;
        Step(int r,int c,int o,int e,String a,String color){row=r;column=c;order=o;entry=e;action=a;this.color=color;original=null;branchOffset=0;}
        Step(Step source,String action,int offset,int entry){row=source.row;column=source.column;order=source.order;this.entry=entry;this.action=action;color=source.color;original=source.source();branchOffset=offset;rowId=source.rowId;}
        Step source(){return original==null?this:original;}
    }
    static final class Row {
        String label,note;int round;boolean continuation;long id;
        final List<Step> steps=new ArrayList<>();
        final String[] cells=new String[5],colors=new String[5];
        String problem="";
    }
    final List<Row> rows=new ArrayList<>();
    final String[] agents=new String[5];
    String roleName,headerNote,tableName,game;
    boolean allowAutoFollow=true,live;
    // 完整编辑快照保留旧格式、重复顺序及自定义数据，不能从执行步骤反推原表。
    String editPayload;
    /** 执行器、循环设置保留同一个表对象；替换内容不会重建悬浮窗或丢失本机设置。 */
    void replace(RecorderFollowTable next){
        rows.clear();rows.addAll(next.rows);System.arraycopy(next.agents,0,agents,0,5);
        roleName=next.roleName;headerNote=next.headerNote;tableName=next.tableName;game=next.game;
        allowAutoFollow=next.allowAutoFollow;live=next.live;editPayload=next.editPayload;
    }
    static RecorderFollowTable parseLive(String json)throws Exception{
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>RecorderLanTransport.MAX_BYTES)throw new IllegalArgumentException("同步表过大");
        JSONObject data=new JSONObject(json);
        if(!"yuan-recorder-live".equals(data.getString("type"))||data.getInt("version")!=1||!(data.get("active") instanceof Boolean))throw new IllegalArgumentException("同步表格式无效");
        JSONObject state=data.getJSONObject("state");JSONArray rounds=state.getJSONArray("rounds");Set<Long> ids=new HashSet<>();
        long nextId=state.getLong("nextId");
        for(int r=0;r<rounds.length();r++){long id=rounds.getJSONObject(r).getLong("id");if(id<=0||id>=nextId||!ids.add(id))throw new IllegalArgumentException("同步回合标识无效");}
        if(!state.has("allowAutoFollow"))state.put("allowAutoFollow",true);
        data.put("type","yuan-recorder-local-table").put("tableName",data.getString("name"));
        RecorderFollowTable table=parseData(data,true);table.live=true;return table;
    }
    static String embedded(byte[] file){
        byte[] magic="YRAFIMG1".getBytes(StandardCharsets.US_ASCII);int n=file.length;
        if(n<12)return null;
        for(int i=0;i<8;i++)if(file[n-8+i]!=magic[i])return null;
        int length=java.nio.ByteBuffer.wrap(file,n-12,4).getInt();
        if(length<7||length>65542||length>n-12)throw new IllegalArgumentException("图片跟打编码损坏");
        return new String(file,n-12-length,length,StandardCharsets.US_ASCII);
    }
    static RecorderFollowTable decode(Context context,String code)throws Exception{
        // 通用码直接认证；只有历史设备码才尝试原机保存的旧密钥，新设备无需配置。
        try{return decode(code,new JSONArray());}catch(MissingKey legacy){
            JSONObject keys=new JSONObject(new RecorderDeviceIdentity(context).readKeys());
            if(!keys.optBoolean("ok"))throw legacy;
            return decode(code,keys.getJSONObject("value").getJSONArray("keys"));
        }
    }
    static RecorderFollowTable decode(String code,JSONArray keys)throws Exception{
        JSONArray accepted=new JSONArray().put(APP_KEY);for(int i=0;i<keys.length();i++)accepted.put(keys.getString(i));keys=accepted;
        if(code.length()>131084)throw new IllegalArgumentException("跟打码过长");
        code=code.replaceAll("\\s","");
        if(!code.matches("YRAF1:[A-Za-z0-9_-]+")||code.length()>65542)throw new IllegalArgumentException("跟打码损坏");
        byte[] bytes=Base64.decode(code.substring(6),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        if(bytes.length<=52||!new String(bytes,0,8,StandardCharsets.US_ASCII).equals("YRAF0001"))throw new IllegalArgumentException("跟打码版本不支持");
        byte[] header=Arrays.copyOf(bytes,36);
        for(int k=0;k<keys.length();k++){
            String key=keys.getString(k);
            if(!MessageDigest.isEqual(Arrays.copyOf(digest("fingerprint",key),16),Arrays.copyOfRange(header,8,24)))continue;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(digest("encryption",key),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(header,24,36)));
            cipher.updateAAD(header);byte[] packed;
            try{packed=cipher.doFinal(bytes,36,bytes.length-36);}catch(Exception error){throw new IllegalArgumentException("跟打码校验失败，请使用原图");}
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InflaterInputStream input=new InflaterInputStream(new ByteArrayInputStream(packed))){
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){if(out.size()+n>1024*1024)throw new IllegalArgumentException("跟打数据过大");out.write(buffer,0,n);}
            }
            return parse(new JSONObject(out.toString("UTF-8")));
        }
        throw new MissingKey();
    }
    private static byte[] digest(String purpose,String key)throws Exception{return MessageDigest.getInstance("SHA-256").digest(("yuan:auto-follow:"+purpose+":v1:"+key).getBytes(StandardCharsets.UTF_8));}
    static RecorderFollowTable parse(JSONObject data)throws Exception{
        return parseData(data,false);
    }
    // 本地表来自 App 的已保存快照；关闭跟打的表也能结构化手抄，不能因此放开图片码认证。
    static RecorderFollowTable parseLocal(String json)throws Exception{
        if(json==null||json.length()>1024*1024||json.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IllegalArgumentException("操作表数据过大");
        return parseData(new JSONObject(json),true);
    }
    private static RecorderFollowTable parseData(JSONObject data,boolean local)throws Exception{
        JSONObject state=data.getJSONObject("state");
        if(!data.getString("type").equals(local?"yuan-recorder-local-table":"yuan-recorder-auto-follow")||data.getInt("version")!=1)throw new IllegalArgumentException("操作表格式不支持");
        if(!local&&!state.getBoolean("allowAutoFollow"))throw new IllegalArgumentException("该表不开放自动跟打");
        RecorderFollowTable table=new RecorderFollowTable();table.game=data.getString("game");
        table.allowAutoFollow=state.getBoolean("allowAutoFollow");
        if(!table.game.equals("ruYuan")&&!table.game.equals("codeYuan"))throw new IllegalArgumentException("游戏类型不支持");
        table.roleName=data.getString("roleName");table.tableName=data.getString("tableName");table.headerNote=state.optString("headerNote","");
        JSONArray agents=data.getJSONArray("agents"),slots=state.getJSONArray("slots"),rounds=state.getJSONArray("rounds");
        if(agents.length()!=5||slots.length()!=5||rounds.length()==0||rounds.length()>200)throw new IllegalArgumentException("跟打表结构无效");
        for(int c=0;c<5;c++){
            if(agents.isNull(c)&&slots.isNull(c))table.agents[c]="未配置";
            else {JSONObject agent=agents.getJSONObject(c);if(!agent.getString("id").equals(slots.getString(c)))throw new IllegalArgumentException("密探列不匹配");table.agents[c]=agent.getString("name");}
        }
        int number=0;
        for(int r=0;r<rounds.length();r++){
            JSONObject source=rounds.getJSONObject(r);Row row=new Row();row.id=source.optLong("id",r+1);String label=source.optString("label","");
            row.continuation=!label.isEmpty();if(!row.continuation)number++;row.round=number;
            row.label=row.continuation?number+"回合·"+label:number+"回合";row.note=source.optString("note","");
            if(number==0)row.problem="首行自定义回合没有所属普通回合";
            JSONArray cells=source.getJSONArray("cells");if(cells.length()!=5)throw new IllegalArgumentException("跟打操作列无效");
            Set<Integer> orders=new HashSet<>();
            for(int c=0;c<5;c++){
                JSONObject cell=cells.getJSONObject(c);String color=cell.getString("background");
                if(!Arrays.asList(COLORS).contains(color))throw new IllegalArgumentException("操作颜色无效");row.colors[c]=color;
                JSONArray entries=entries(cell);StringBuilder display=new StringBuilder();
                for(int e=0;e<entries.length();e++){
                    JSONObject entry=entries.getJSONObject(e);int order=entry.getInt("order");String action=entry.getString("action");
                    if(order==0){java.util.regex.Matcher m=java.util.regex.Pattern.compile("([1-9][0-9]{0,3})(A|↑|↓|圈)").matcher(action);if(m.matches()){order=Integer.parseInt(m.group(1));action=m.group(2);}}
                    // 展示文字按操作分行，执行器仍使用独立的步骤列表和原始顺序。
                    if(display.length()>0)display.append('\n');display.append(order>0?order:"").append(action);
                    if(order<=0||!Arrays.asList("A","↑","↓","圈").contains(action))row.problem="存在无法执行的自定义操作："+action;
                    if(!orders.add(order))row.problem="本行存在重复操作顺序："+order;
                    if(slots.isNull(c))row.problem="第 "+(c+1)+" 列未配置密探";
                    Step step=new Step(r,c,order,e,action,color);step.rowId=row.id;row.steps.add(step);
                }
                row.cells[c]=display.toString();
            }
            row.steps.sort(Comparator.comparingInt(s->s.order));table.rows.add(row);
        }
        JSONObject snapshot=new JSONObject(data.toString());
        snapshot.put("type","yuan-recorder-local-table");table.editPayload=snapshot.toString();
        return table;
    }
    private static JSONArray entries(JSONObject cell)throws Exception{
        if(cell.has("entries"))return cell.getJSONArray("entries");
        JSONArray result=new JSONArray();String mode=cell.getString("mode");
        if(mode.equals("custom")){String text=cell.getString("custom");if(!text.isEmpty())result.put(new JSONObject().put("order",0).put("action",text));}
        else if(mode.equals("old")){String action=cell.getString("action");if(!action.isEmpty())result.put(new JSONObject().put("order",cell.getInt("order")).put("action",action));}
        else if(mode.equals("new")){JSONArray orders=cell.getJSONArray("orders");for(int i=0;i<orders.length();i++)if(!orders.getString(i).isEmpty())result.put(new JSONObject().put("order",i+1).put("action",orders.getString(i)));}
        else throw new IllegalArgumentException("操作格式不支持");return result;
    }
    List<Step> steps(int round){
        if(rows.get(0).round==0)throw new IllegalArgumentException(rows.get(0).problem);
        List<Step> result=new ArrayList<>();boolean found=false;
        for(Row row:rows)if(row.round==round){found=true;if(!row.problem.isEmpty())throw new IllegalArgumentException(row.label+"："+row.problem);result.addAll(row.steps);}
        if(!found)throw new IllegalArgumentException("操作表无第 "+round+" 回合");return result;
    }
    /** 统计整张表（含续行）的操作底色；无色也提供站位映射，填 0 才保持当前目标。 */
    List<String> usedColors(){
        Set<String> present=new HashSet<>();
        for(Row row:rows){present.addAll(Arrays.asList(row.colors));for(Step step:row.steps)present.add(step.color);}
        List<String> result=new ArrayList<>();
        // 保持既有颜色顺序，避免换一张表后同色设置的上下顺序随机变化。
        for(String color:COLORS)if(present.contains(color))result.add(color);
        return result;
    }
    static int fill(String color){return FILLS[Arrays.asList(COLORS).indexOf(color)];}
}
