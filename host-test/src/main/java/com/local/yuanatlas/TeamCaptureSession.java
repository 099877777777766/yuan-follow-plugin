package com.local.yuanatlas;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 配队只读采集：每次确认页面后推进，五人封顶，不触碰培养、装备或改命按钮。 */
final class TeamCaptureSession {
    final JSONObject result=new JSONObject();
    final boolean codeYuan;
    double width,height,left,top,contentHeight;
    private double screenWidth;
    private String state="main";
    private JSONObject member;
    private final List<Integer> cells=new ArrayList<>();
    private int currentCell,steps,frameRetries,selectionRetries;
    private int detailsRetries;
    private android.graphics.Rect entryCaptureBounds;
    private JSONArray cellTargets;
    static final class LayoutException extends Exception { LayoutException(String message){super(message);} }
    TeamCaptureSession(JSONObject config)throws Exception{
        String game=config.getString("game");
        codeYuan="codeYuan".equals(game);
        if(!game.equals("ruYuan")&&!game.equals("codeYuan"))throw new Exception("采集游戏无效");
        if(!config.has("roleId"))throw new Exception("缺少角色范围");
        result.put("version",1).put("sessionId",UUID.randomUUID().toString()).put("game",game).put("roleId",config.getString("roleId"))
            .put("phase","collecting").put("members",new JSONArray()).put("warnings",new JSONArray());
    }
    String stage(){return state;}
    android.graphics.Rect captureBounds(int w,int h){return codeYuan&&screenWidth==w&&height==h?entryCaptureBounds:null;}
    String currentName(){return member==null?"":member.optString("name");}
    String identityLabel(){JSONObject p=result.optJSONObject("identityPreview");return p==null?"":("第"+p.optInt("slot")+"位 · "+(p.optString("name").isEmpty()?"姓名未识别":p.optString("name"))+" · ");}
    boolean done(){return "ready".equals(result.optString("phase"));}
    void finish(String reason)throws Exception{if(!reason.isEmpty())result.getJSONArray("warnings").put(reason);result.put("phase","ready");}
    private void require(boolean condition,String message)throws Exception{if(!condition)throw new LayoutException(message);}
    /** 动画或单帧 OCR 抖动只重新截图，不重复点击；连续失败后才暂停。 */
    RuYuanAutoCapture.Action retryFrame(LayoutException error)throws Exception{
        if(++frameRetries<=2)return RuYuanAutoCapture.Action.read("重新识别当前页面（"+frameRetries+"/2）");
        frameRetries=0;throw new Exception(error.getMessage()+"；已暂停，恢复当前页面后继续");
    }
    private RuYuanAutoCapture.Action tap(String next,String label,double x,double y){frameRetries=0;state=next;return RuYuanAutoCapture.Action.tap(label,x,y);}
    private RuYuanAutoCapture.Action back(String next,String label){return tap(next,label,.10,.045);}
    /** 输入只接受 OCR 当前帧。页面不符时保持步骤，恢复不会跳过未采集字段。 */
    RuYuanAutoCapture.Action accept(JSONObject page)throws Exception{
        screenWidth=page.getDouble("width");left=page.optDouble("left",0);width=page.optDouble("contentWidth",screenWidth);height=page.getDouble("height");top=page.optDouble("top");contentHeight=page.optDouble("contentHeight",height);
        require(width/contentHeight>.40&&width/contentHeight<.63,"请恢复视频中的竖屏布局");
        if(++steps>180)throw new Exception("已达到本次采集步骤上限，请结束并核对结果");
        String text=region(page,0,0,1,1);
        String controls=region(page,.30,.76,1,.94);
        // 竖排“命”“盘”常被分成不相邻的 OCR 行，只在底部培养按钮区域组合判断。
        boolean main=text.contains("修为")&&text.contains("天赋")&&text.contains("命盘")
            ||controls.contains("修为")&&controls.contains("化极")&&controls.contains("命");
        if(codeYuan){
            // 自定义命盘名会替换“命盘一”，竖排“化/极”也可能夹入标点；按按钮各自区域判断。
            String rankControls=region(page,.30,.78,.48,.95),evolutionControls=region(page,.55,.78,.69,.95);
            main|=rankControls.contains("修")&&rankControls.contains("为")&&evolutionControls.contains("化")&&evolutionControls.contains("极")&&text.contains("详情")&&text.contains("生命")&&text.contains("攻击");
        }
        // 未解锁改命时没有“逆天改命”，以命盘页面独有的星石方案和装备控件定位。
        boolean fate=(text.contains("逆天")||text.contains("星石方案")&&text.contains("命盘"))&&text.matches(".*[键健][装裝卸].*")&&!text.contains("再次改命");
        result.put("phase","collecting").put("step",state);
        if(state.equals("main")||state.equals("next")){
            require(main,"未定位密探立绘页");
            // 定位成功后才追加成员，截图重试不能把同一人重复写入草稿。
            if(codeYuan)require(page.has("teamDetailsX")&&page.has("teamDetailsY"),"未定位当前密探的详情入口");
            if(entryCaptureBounds==null||entryCaptureBounds.width()!=width||entryCaptureBounds.left!=left)entryCaptureBounds=new android.graphics.Rect((int)left,(int)top,(int)(left+width),(int)(top+contentHeight));
            detailsRetries=0;
            // 身份在密探入口建立，后续详情、命盘和星石始终归入这一名密探。
            String name=page.optString("teamName");
            JSONArray previous=result.getJSONArray("members"),conflicts=new JSONArray();
            for(int i=0;i<previous.length();i++)if(!name.isEmpty()&&name.equals(previous.getJSONObject(i).optString("name")))conflicts.put(i+1);
            // 在重复校验之前保留候选，暂停时也能看到导致冲突的新截图与姓名。
            result.put("identityPreview",new JSONObject().put("slot",previous.length()+1).put("name",name).put("rawName",page.optString("teamNameText")).put("conflicts",conflicts).put("preview",page.optString("preview")));
            if(state.equals("next")){
                require(conflicts.length()==0,"当前识别为“"+name+"”，与第"+conflicts.optInt(0)+"位重复，请查看入口预览并确认已切换密探");
            }
            member=new JSONObject().put("name",name).put("config",new JSONObject()).put("warnings",new JSONArray()).put("evidence",new JSONArray());
            result.getJSONArray("members").put(member);evidence(page,"密探资料");
            JSONObject config=member.getJSONObject("config");
            number(config,"level",page.optString("teamLevel"),1,100);
            number(config,"rank",page.optString("teamRank"),1,99);
            if(codeYuan){number(config,"life",page.optString("teamLife"),0,100000000);number(config,"attack",page.optString("teamAttack"),0,100000000);}
            // 100级已到上限；其他等级的突破状态只取升级页明确文字，不猜测。
            if(config.optInt("level")==100)config.put("breakthrough",false);
            if(page.has("teamDetailsX")&&page.has("teamDetailsY")){
                return tap("details","读取生命、攻击和蝶",page.getDouble("teamDetailsX"),page.getDouble("teamDetailsY"));
            }
            return tap("details","读取生命、攻击和蝶",.103,.715);
        }
        if(state.equals("details")){
            // 使用多个独立标题定位弹窗，单个“奇闻”错字不应中断两种蝶的录入。
            int anchors=(text.contains("属性详情")?1:0)+(text.contains("总属性")?1:0)+(text.contains("奇闻属性")?1:0)+(text.contains("探索能力")?1:0);
            // 只在仍明确位于立绘页时补点，不将命盘或未知页面当作详情，也不追加成员。
            if(anchors<2&&codeYuan&&main&&page.has("teamDetailsX")&&page.has("teamDetailsY")&&detailsRetries++<2)return tap("details","重新打开当前密探属性详情",page.getDouble("teamDetailsX"),page.getDouble("teamDetailsY"));
            require(anchors>=2,"未定位属性详情");evidence(page,"属性与蝶");
            detailsRetries=0;
            JSONObject c=member.getJSONObject("config");
            number(c,"life",page.optString("teamLife"),0,100000000);
            number(c,"attack",page.optString("teamAttack"),0,100000000);
            fraction(c,"anecdoteAttack",page.optString("teamButterflyAttack"));fraction(c,"anecdoteLife",page.optString("teamButterflyLife"));
            return tap("details-closed","关闭属性详情",page.optDouble("teamDetailsCloseX",codeYuan?.93:.967),page.optDouble("teamDetailsCloseY",codeYuan?.23:.216));
        }
        if(state.equals("details-closed")){
            require(main&&!text.contains("属性详情"),"属性详情尚未关闭");
            return tap("evolution","读取星级与化极",.606,.853);
        }
        if(state.equals("evolution")){
            // 代号鸢低清录屏的“消耗”易读错；已确认星与节点时，用属性提升文字作为独立页面依据。
            boolean cultivation=codeYuan&&page.has("teamEvolution")&&(text.contains("生命值+")||text.contains("攻击力+"));
            // 已觉醒和五星待觉醒使用另一套界面，不显示“化极/消耗”；视觉确认后可直接录入。
            require((text.contains("化极")&&(text.contains("消耗")||page.optBoolean("teamEvolutionPreview")||cultivation)||page.optBoolean("teamAwakeningPage")||page.optBoolean("teamEvolutionPage"))&&!main,"未定位化极详情");evidence(page,"星级化极");
            if(page.has("teamEvolution"))member.getJSONObject("config").put("evolution",page.getInt("teamEvolution"));
            else member.getJSONArray("warnings").put("星级 / 化极未识别，请在结果中补填");
            return back("evolution-closed","返回密探资料");
        }
        if(state.equals("evolution-closed")){
            require(main,"未返回密探立绘页");return tap("fate","读取当前生效命盘",.85,.857);
        }
        if(state.equals("fate")){
            require(fate,"未定位当前装备的命盘页");evidence(page,"当前命盘");
            // 入口缺名时只补全一次身份；补读失败仍继续，交由结果预览手选。
            if(currentName().isEmpty()&&!page.optString("teamFateName").isEmpty()){
                String candidate=page.getString("teamFateName");JSONArray members=result.getJSONArray("members");
                for(int n=0;n<members.length()-1;n++)require(!candidate.equals(members.getJSONObject(n).optString("name")),"命盘识别为已采集的“"+candidate+"”，请确认已切换下一名密探");
                member.put("name",page.getString("teamFateName"));JSONObject identity=result.optJSONObject("identityPreview");
                if(identity!=null)identity.put("name",currentName());
            }
            JSONArray grid=page.optJSONArray("teamCells");
            if(codeYuan)require(grid!=null&&grid.length()==12,"未定位当前命盘网格");
            if(grid==null||grid.length()!=12){
                member.getJSONArray("warnings").put("命盘格未识别完整，请在结果中补填命盘和星石");
                return nextMember();
            }
            List<Integer> active=new ArrayList<>();JSONArray titles=new JSONArray();
            // 每四格一组；读不出某组时跳过它，继续读取其他已定位组的星石。
            for(int group=0;group<3;group++){
                int count=0,selected=-1;boolean locked=false;
                for(int i=group*4;i<group*4+4;i++){JSONObject cell=grid.getJSONObject(i);if(cell.optBoolean("active")){count++;selected=i;}locked|=cell.optBoolean("locked");}
                if(count==1){
                    active.add(selected);
                    String title=grid.getJSONObject(selected).optString("title");
                    if(title.isEmpty())member.getJSONArray("warnings").put("第"+(group+1)+"组生效命盘标题未识别，请在结果中补填");
                    else titles.put(title);
                }else if(count!=0||!locked)member.getJSONArray("warnings").put("第"+(group+1)+"组命盘未确认生效或未解锁，请在结果中补填");
            }
            cells.clear();cells.addAll(active);currentCell=0;cellTargets=page.optJSONArray("teamCellTargets");
            JSONArray mains=new JSONArray(),aux=new JSONArray();
            for(int group=0;group<3;group++){boolean unlocked=false;for(int cell:active)if(cell/4==group)unlocked=true;mains.put(unlocked?JSONObject.NULL:new JSONObject().put("name","").put("level","1"));aux.put(unlocked?JSONObject.NULL:new JSONObject().put("name","").put("level","1"));}
            member.getJSONObject("config").put("fates",titles).put("mainStars",mains).put("auxiliaryStars",aux);
            if(cells.isEmpty())return nextMember();
            return cellAction();
        }
        if(state.equals("stars")){
            require(fate,"星石详情已离开当前命盘页");
            // 被选格有高亮边框才读取详情，防止动画或失败的手势使上一格星石重复归入。
            if(page.optInt("teamSelectedCell",-1)!=cells.get(currentCell)){
                // 沿用入口身份并确认命盘页面，只补点目标格；成功确认前不写入星石、不跳格。
                if(selectionRetries++<2)return tapCell("重新点选第"+(currentCell+1)+"组命盘（"+selectionRetries+"/2）");
                member.getJSONArray("warnings").put("第"+(cells.get(currentCell)/4+1)+"组星石选中框未确认，请在结果中补填");
                if(++currentCell<cells.size())return cellAction();
                return nextMember();
            }
            selectionRetries=0;
            evidence(page,"命盘星石"+(currentCell+1));
            JSONObject c=member.getJSONObject("config");
            int group=cells.get(currentCell)/4;
            c.getJSONArray("mainStars").put(group,page.optJSONObject("teamMainStar")==null?new JSONObject():page.getJSONObject("teamMainStar"));
            c.getJSONArray("auxiliaryStars").put(group,page.optJSONObject("teamAuxiliaryStar")==null?new JSONObject():page.getJSONObject("teamAuxiliaryStar"));
            for(String key:new String[]{"teamMainStar","teamAuxiliaryStar"}){JSONObject star=page.optJSONObject(key);if(star!=null&&star.optBoolean("breakthroughUnknown"))member.getJSONArray("warnings").put("第"+(group+1)+"组"+(key.equals("teamMainStar")?"主星":"辅星")+star.optString("level")+"级突破状态未识别，可在配队表修改");}
            if(++currentCell<cells.size())return cellAction();
            return nextMember();
        }
        if(state.equals("member-closed")){
            require(main,"未返回密探立绘页");return tap("next","切换下一名密探（"+(result.getJSONArray("members").length()+1)+"/5）",codeYuan?.955:.982,codeYuan?.503:.508);
        }
        throw new Exception("未知采集步骤");
    }
    private RuYuanAutoCapture.Action nextMember()throws Exception{if(result.getJSONArray("members").length()==5){finish("");return null;}return back("member-closed","返回并切换下一名密探");}
    private RuYuanAutoCapture.Action cellAction(){selectionRetries=0;return tapCell("读取第"+(currentCell+1)+"组主辅星");}
    private RuYuanAutoCapture.Action tapCell(String label){
        int index=cells.get(currentCell);JSONObject target=cellTargets==null?null:cellTargets.optJSONObject(index);
        // 点选与 OCR 使用同一次定位的网格中心，避免两套比例在切换布局时产生偏差。
        if(target!=null)return tap("stars",label,target.optDouble("x"),target.optDouble("y"));
        int[] cell=RuYuanAutoCapture.CELLS[index];return tap("stars",label,.138+cell[0]*.24,(codeYuan?.270:.261)+cell[1]*(codeYuan?.113:.118));
    }
    private void evidence(JSONObject page,String stage)throws Exception{
        if(!page.has("preview"))return;JSONArray shots=member.getJSONArray("evidence");
        // 暂停后重复识别只更新该步骤截图，避免重试积累图片使草稿超过读取上限。
        for(int i=0;i<shots.length();i++)if(stage.equals(shots.getJSONObject(i).optString("stage"))){shots.getJSONObject(i).put("preview",page.getString("preview"));return;}
        if(shots.length()<10)shots.put(new JSONObject().put("stage",stage).put("preview",page.getString("preview")));
    }
    static String clean(String s){return s.replaceAll("\\s+","").replace('盤','盘').replace('級','级').replace('為','为').replace('賦','赋').replace('屬','属').replace('詳','详').replace('聞','闻').replace('鍵','键').replace('極','极').replace('諸','诸').replace('葛','葛').replace('龐','庞').replace('張','张').replace('遼','辽').replace('寶','宝').replace('華','华').replace('權','权').replace('孫','孙').replace('滿','满').replace('寵','宠');}
    static String region(JSONObject page,double x1,double y1,double x2,double y2)throws Exception{
        StringBuilder out=new StringBuilder();JSONArray rows=page.getJSONArray("lines");double l=page.optDouble("left",0),w=page.optDouble("contentWidth",page.getDouble("width")),t=page.optDouble("top"),h=page.optDouble("contentHeight",page.getDouble("height"));
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);double x=(row.optDouble("x")+row.optDouble("width")/2-l)/w,y=(row.optDouble("y")+row.optDouble("height")/2-t)/h;if(x>=x1&&x<=x2&&y>=y1&&y<=y2)out.append(row.optString("text")).append(' ');}
        return clean(out.toString());
    }
    private static void number(JSONObject c,String key,String raw,int min,int max)throws Exception{String value=clean(raw).replaceAll("^(?:等级|修为|生命|攻击|Lv\\.?|LV\\.?|\\+)","");if(value.matches("[0-9]{1,9}")){int n=Integer.parseInt(value);if(n>=min&&n<=max)c.put(key,n);}}
    private static void fraction(JSONObject c,String key,String raw)throws Exception{Matcher m=Pattern.compile("^(\\d{1,5})[/／](\\d{1,5})$").matcher(clean(raw));if(m.matches()){int current=Integer.parseInt(m.group(1)),limit=Integer.parseInt(m.group(2));if(current<=limit)c.put(key,current);}}
}
