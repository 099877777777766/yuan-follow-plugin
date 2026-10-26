package com.local.yuanatlas;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 视频顺序的有界状态机。字段识别失败记入结果，只有失去页面定位才暂停手势。 */
final class RuYuanAutoCapture {
    static final String[] KEYS={"normal","active","leader","talents","fate"};
    static final String[] LABELS={"普攻","技能","队长技能","天赋","命盘"};
    static final int[][] CELLS={{0,0},{1,0},{2,0},{3,0},{3,1},{3,2},{3,3},{2,3},{1,3},{0,3},{0,2},{0,1}};
    static final class Action {
        final String kind,label; final double x,y,toY;
        Action(String kind,String label,double x,double y,double toY){this.kind=kind;this.label=label;this.x=x;this.y=y;this.toY=toY;}
        static Action read(String label){return new Action("read",label,0,0,0);}
        static Action tap(String label,double x,double y){return new Action("tap",label,x,y,y);}
    }
    final JSONObject result=new JSONObject();
    private String state="main",talentFingerprint="",previousTalentPixels="";
    private int scrolls,cell,steps,dismissAttempts,layoutRetries;
    static final class LayoutException extends Exception {LayoutException(String message){super(message);}}

    private List<JSONObject> previousTalentRows=new ArrayList<>();
    private final JSONArray fates=new JSONArray();
    private final JSONArray fateCache;
    final boolean codeYuan;
    private boolean dualGrid;
    private final Map<String,JSONObject> talents=new LinkedHashMap<>();
    double width,height,left,top,contentHeight;
    private JSONArray skillTargets;
    private double gridLeft,gridTop,gridCellWidth,gridCellHeight;
    private String gridViewport="";
    RuYuanAutoCapture(JSONObject config)throws Exception{
        codeYuan="codeYuan".equals(config.optString("game"));
        result.put("game",codeYuan?"codeYuan":"ruYuan");
        fateCache=config.optJSONArray("fateCache")==null?new JSONArray():config.getJSONArray("fateCache");
        result.put("version",1).put("sessionId",UUID.randomUUID().toString()).put("expectedRevision",config.optInt("revision",0)).put("phase","collecting");
        result.put("mode",config.optString("mode","new"));if(config.has("targetAgentId"))result.put("targetAgentId",config.getString("targetAgentId"));
        result.put("identity",new JSONObject().put("name","").put("subtitle","").put("attribute","").put("role","").put("rarity",""));
        JSONObject sections=new JSONObject();for(String key:KEYS)sections.put(key,new JSONArray());
        result.put("sections",sections).put("warnings",new JSONArray()).put("evidence",new JSONArray()).put("visitedFates",0).put("expectedFates",12);
    }
    String stage(){return state;}
    boolean done(){return "ready".equals(result.optString("phase"));}
    void warning(String value)throws Exception{JSONArray list=result.getJSONArray("warnings");for(int i=0;i<list.length();i++)if(value.equals(list.getString(i)))return;list.put(value);}
    void finish(String reason)throws Exception{
        if(!reason.isEmpty())warning(reason);
        for(String key:KEYS)if(result.getJSONObject("sections").getJSONArray(key).length()==0)warning(label(key)+"尚未识别到内容，可在结果页补充");
        JSONObject identity=result.getJSONObject("identity");String[] fields={"name","attribute","role","rarity"},names={"姓名","属性","职业","品质"};for(int i=0;i<fields.length;i++)if(identity.optString(fields[i]).isEmpty())warning("基础资料待补充："+names[i]);
        if(codeYuan){JSONArray unique=new JSONArray();Map<String,JSONObject> seen=new LinkedHashMap<>();for(int i=0;i<fates.length();i++){JSONObject row=fates.getJSONObject(i);String key=row.optString("title")+"|"+row.optString("quality");if(row.optString("title").isEmpty()){unique.put(row);continue;}JSONObject old=seen.get(key);if(old==null){seen.put(key,row);unique.put(row);}else if(old.optString("description").isEmpty())old.put("description",row.optString("description"));}result.getJSONObject("sections").put("fate",unique);}
        // 不确定的百分号保留原文，结果页定位到具体条目供核对，不强制猜数值。
        for(String key:KEYS){JSONArray entries=result.getJSONObject("sections").getJSONArray(key);for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(item.optString("description").matches("(?s).*[0-9]+(?:96|06|9[%％])(?![0-9]).*"))warning(label(key)+"第"+(i+1)+"条（"+item.optString("title")+"）含疑似百分号误识别，请对照截图核对数值");}}
        result.put("phase","ready");state="finished";
    }
    private String label(String key){for(int i=0;i<KEYS.length;i++)if(KEYS[i].equals(key))return LABELS[i];return key;}
    private void requireSkillTarget(int index)throws LayoutException{
        // 9:16 短屏缺少定位证据时保持当前步骤，不能点击历史长屏位置。
        if((skillTargets==null||skillTargets.optJSONObject(index)==null)&&width/contentHeight>.53)throw new LayoutException("未定位当前技能图标，请保持技能栏可见后继续");
    }
    private Action nextSkill(int index)throws Exception{
        requireSkillTarget(index);JSONObject target=skillTargets==null?null:skillTargets.optJSONObject(index);
        state=KEYS[index];return Action.tap("读取"+LABELS[index],target==null?(codeYuan?new double[]{.451,.601,.751,.9}:new double[]{.537,.674,.807,.941})[index]:target.getDouble("x"),target==null?(codeYuan?.724:.718):target.getDouble("y"));
    }
    /** 页面漏字只重读当前步骤，既不点下一项，也不把浮窗提示写入资料。 */
    Action retryLayout()throws Exception{return retryLayout(null);}
    Action retryLayout(LayoutException cause)throws Exception{
        String detail=cause==null?"":"："+cause.getMessage();
        if(++layoutRetries>3){layoutRetries=0;throw new Exception("连续无法定位密探资料页，已保留进度"+detail+"；恢复游戏资料页后继续，采集浮窗无需收起");}
        return Action.read("重新定位密探资料（"+layoutRetries+"/3）"+detail);
    }
    private void validateLayout(List<JSONObject> rows)throws LayoutException{
        boolean codeEntry=codeYuan&&(state.equals("main")||state.equals("fate-home"))&&(codeHome(rows)||codeDual(rows));
        if(!codeEntry&&(state.equals("main")||state.equals("normal")||state.equals("active")||state.equals("active-expanded")||state.equals("leader")||state.equals("talents"))&&!main(rows))throw new LayoutException("暂未定位密探立绘资料页");
        if(state.equals("fate-home")&&!codeEntry&&!fateHome(rows))throw new LayoutException("暂未定位命盘页");
        if((state.equals("fate-grid")||state.equals("fate-hold")||state.equals("fate-dismiss"))&&!fateGrid(rows))throw new LayoutException("暂未定位命盘详情网格");
        if(state.startsWith("code-fate")&&!(dualGrid?codeDual(rows):codeHome(rows)))throw new LayoutException("暂未定位代号鸢命盘页");
    }
    Action accept(JSONObject page)throws Exception{
        // 页面文字可定位不代表下一技能入口已定位；整步成功后才能清零重试次数。
        Action next=advance(page);layoutRetries=0;return next;
    }
    private Action advance(JSONObject page)throws Exception{
        left=page.optDouble("left",0);width=page.optDouble("contentWidth",page.getDouble("width"));height=page.getDouble("height");top=page.optDouble("top",0);contentHeight=page.optDouble("contentHeight",height);
        skillTargets=page.optJSONArray("skillTargets");
        if(width/contentHeight<.40||width/contentHeight>.63)throw new LayoutException("当前不是视频对应的竖屏布局，请恢复竖屏后继续");
        // 必须先确认页面，再改身份、证据和步骤，保证重读不会污染已采集资料。
        List<JSONObject> lines=lines(page);validateLayout(lines);
        if("fate-grid".equals(state))locateGrid(page);
        // 弹窗可能遮挡边线，沿用同一画面已确认的网格；窗口尺寸变化时不能复用旧点击位置。
        if(("fate-hold".equals(state)||"fate-dismiss".equals(state))&&!gridViewport.equals(viewportKey()))throw new LayoutException("命盘画面尺寸已变化，请恢复采集时的游戏窗口后继续");
        // 先检查本步必要的下一入口，再写身份、预览和技能，失败重读不能重复追加内容。
        if("main".equals(state)&&!(codeYuan&&(codeHome(lines)||codeDual(lines))))requireSkillTarget(0);
        else if("normal".equals(state))requireSkillTarget(1);
        else if("active-expanded".equals(state)||"active".equals(state)&&!(codeYuan&&find(region(lines,.25,.35,.9,.68),"详[细情]")!=null))requireSkillTarget(2);
        else if("leader".equals(state))requireSkillTarget(3);
        if(++steps>90){finish("已达到本次采集步数上限，未完成部分可补充");return null;}
        result.put("step",state).put("phase","collecting");if(!state.startsWith("fate")&&!state.startsWith("code-fate")&&(!codeYuan||main(lines)))identity(page,lines);
        if(page.has("preview")&&result.getJSONArray("evidence").length()<40)result.getJSONArray("evidence").put(new JSONObject().put("stage",state).put("preview",page.getString("preview")));
        if(codeYuan&&("main".equals(state)||"fate-home".equals(state))&&(codeHome(lines)||codeDual(lines))){
            dualGrid=codeDual(lines);if(!dualGrid)readNameFromFate(lines);else warning("当前为改命对照页，仅采集两组命盘；基础资料和技能请从立绘页补充");
            readCodeGrid(page,lines);reuseFates();result.getJSONObject("sections").put("fate",fates);cell=0;return nextCodeFate();
        }
        if(codeYuan&&state.startsWith("code-fate"))return acceptCodeFate(page,lines);
        if("main".equals(state)){
            if(!main(lines))throw new Exception("请进入密探立绘资料页，确保下方普攻、技能、队长技能、天赋可见");
            if(page.has("portrait"))result.put("portrait",page.getString("portrait"));
            return nextSkill(0);
        }
        if("normal".equals(state)||"active".equals(state)||"leader".equals(state)||"active-expanded".equals(state)){
            if(!main(lines))throw new Exception("密探技能页已变化，已暂停；恢复资料页后继续");
            if(codeYuan&&"active".equals(state)){JSONObject expand=find(region(lines,.25,.35,.9,.68),"详[细情]");if(expand!=null){state="active-expanded";return Action.tap("展开技能等级详情",cx(expand),cy(expand));}}
            // 下一入口未定位时先重读，不能先追加正文后抛错，否则重试会重复写入同一技能。
            requireSkillTarget("normal".equals(state)?1:("active".equals(state)||"active-expanded".equals(state))?2:3);
            String skill="active-expanded".equals(state)?"active":state;readSkill(page,lines,skill);return "normal".equals(state)?nextSkill(1):("active".equals(state)||"active-expanded".equals(state))?nextSkill(2):nextSkill(3);
        }
        if("talents".equals(state)){
            if(!main(lines))throw new Exception("天赋页已变化，已暂停");
            List<JSONObject> body=region(lines,.34,.07,.94,.66);readTalents(body);
            // 每次滚动都更新快照，提前结束也能查看已经取得的天赋。
            JSONArray captured=new JSONArray();for(JSONObject item:talents.values())captured.put(item);result.getJSONObject("sections").put("talents",captured);
            String fingerprint=join(body).replaceAll("[^\\p{IsHan}0-9]","");
            boolean hasPixels=!page.optString("talentPixels").isEmpty()&&!previousTalentPixels.isEmpty();
            boolean end=(hasPixels&&stillPixels(page.optString("talentPixels"),previousTalentPixels))||stationaryRows(body,previousTalentRows)||!hasPixels&&!fingerprint.isEmpty()&&similar(fingerprint,talentFingerprint)>.95;
            previousTalentRows=new ArrayList<>(body);
            previousTalentPixels=page.optString("talentPixels");
            talentFingerprint=fingerprint;
            if(!end&&scrolls++<7)return new Action("swipe","滚动读取天赋（"+scrolls+"）",.74,.60,.22);
            if(!end)warning("天赋滚动未确认到底，请核对是否漏项");
            JSONArray entries=new JSONArray();for(JSONObject item:talents.values()){entries.put(item);if(item.optString("unlock").isEmpty())warning("天赋“"+item.optString("title")+"”未显示或未识别到解锁条件，请核对补充");}result.getJSONObject("sections").put("talents",entries);
            state="fate-home";return Action.tap("进入命盘",codeYuan?.86:.83,codeYuan?.854:.846);
        }
        if("fate-home".equals(state)){
            if(!fateHome(lines))throw new Exception("未定位到命盘页；请勿进入战斗或其他菜单");
            readNameFromFate(lines);
            // 直接进入可读名称的详情网格，已有命盘不再重复点按和长按。
            JSONObject anchor=find(lines,"逆天[改政]命");state="fate-grid";return Action.tap("打开命盘详情网格",cx(anchor),cy(anchor));
        }
        if("fate-grid".equals(state)){
            if(!fateGrid(lines))throw new Exception("未定位到长按命盘详情网格");
            readGrid(lines);reuseFates();result.getJSONObject("sections").put("fate",fates);cell=0;state="fate-hold";return nextFate();
        }
        if("fate-hold".equals(state)){
            if(!fateGrid(lines))throw new Exception("命盘详情页已变化，已暂停");
            JSONObject item=fates.getJSONObject(cell);String description=popupDescription(page);
            item.put("description",description);if(description.isEmpty())warning("命盘第"+(cell+1)+"格说明未识别，可在结束后补充");
            result.put("visitedFates",result.optInt("visitedFates")+1);result.getJSONObject("sections").put("fate",fates);
            // 游戏提示松手后仍保留，必须先点击空白关闭，再读取下一格。
            state="fate-dismiss";dismissAttempts=0;return Action.tap("关闭当前命盘描述",.5,.70);
        }
        if("fate-dismiss".equals(state)){
            if(!fateGrid(lines))throw new Exception("关闭描述后未定位到命盘网格，已暂停");
            if(page.has("popup")){
                if(++dismissAttempts<=2)return Action.tap("再次关闭命盘描述",.5,.70);
                throw new Exception("命盘描述尚未关闭，请点空白处关闭后继续");
            }
            cell++;state="fate-hold";return nextFate();
        }
        return null;
    }
    private boolean codeHome(List<JSONObject> rows){return find(region(rows,.02,.86,.99,.94),"一键.*[装备卸下]")!=null&&find(region(rows,.6,.13,1,.22),"逆天.*命")!=null;}
    private boolean codeDual(List<JSONObject> rows){return find(region(rows,.2,.86,1,.94),"再次.*命")!=null&&find(region(rows,.5,.86,1,.94),"接受.*果")!=null;}
    private double codeX(int index){return .138+CELLS[index%12][0]*.24;}
    private double codeY(int index){return dualGrid?(index<12?.185:.548)+CELLS[index%12][1]*.086:.270+CELLS[index%12][1]*.113;}
    private void readCodeGrid(JSONObject page,List<JSONObject> rows)throws Exception{
        int total=dualGrid?24:12;result.put("expectedFates",total);
        for(int i=0;i<total;i++){
            double x=codeX(i),y=codeY(i),half=dualGrid?.040:.052;List<JSONObject> words=new ArrayList<>();Map<String,Integer> votes=new LinkedHashMap<>();boolean locked=false;
            for(JSONObject row:page.has("codeCells")?lines(new JSONObject().put("lines",page.getJSONArray("codeCells").getJSONArray(i))):region(rows,x-.116,y-half,x+.116,y+half)){
                String text=clean(row.optString("text"));if(text.contains("信赖")||text.equals("可解锁")){locked=true;continue;}
                if(text.matches(".*解锁.*|生效中|[0-9]+|[+十]"))continue;words.add(row);String ink=row.optString("ink");if(!ink.isEmpty())votes.put(ink,votes.getOrDefault(ink,0)+1);
            }
            String name=join(words).replaceAll("[\\s|「」]","");String quality="";int best=0;for(Map.Entry<String,Integer> vote:votes.entrySet())if(vote.getValue()>best){quality=vote.getKey();best=vote.getValue();}
            JSONObject entry=entry(locked?"":name,"",quality).put("cell",i+1).put("locked",locked);fates.put(entry);
            if(locked||name.isEmpty())warning("命盘第"+(i+1)+"格未解锁或名称未识别，请补充或移除待补条目");
        }
    }
    private Action nextCodeFate()throws Exception{
        while(cell<fates.length()){
            JSONObject item=fates.getJSONObject(cell);
            if("catalog".equals(item.optString("source"))){cell++;continue;}
            // 两组对照盘可能出现相同命盘，只读取一次完整描述，仍按宫格推进进度。
            if(!item.optString("title").isEmpty())for(int i=0;i<cell;i++){JSONObject old=fates.getJSONObject(i);if(item.optString("title").equals(old.optString("title"))&&item.optString("quality").equals(old.optString("quality"))&&!old.optString("description").isEmpty()){item.put("description",old.getString("description")).put("source","captured");break;}}
            if(item.optBoolean("locked")||"captured".equals(item.optString("source"))){result.put("visitedFates",result.optInt("visitedFates")+1);cell++;continue;}
            state="code-fate-read";return new Action(dualGrid?"hold":"tap","读取代号鸢命盘 "+(cell+1)+"/"+fates.length(),codeX(cell),codeY(cell),codeY(cell));
        }
        finish("");return null;
    }
    private Action acceptCodeFate(JSONObject page,List<JSONObject> rows)throws Exception{
        if(!(dualGrid?codeDual(rows):codeHome(rows)))throw new Exception("代号鸢命盘页面已变化，已暂停");
        if("code-fate-dismiss".equals(state)){
            if(page.has("popup")){if(++dismissAttempts<=2)return Action.tap("关闭命盘描述",.50,.97);throw new Exception("请先关闭命盘描述后继续");}
            cell++;return nextCodeFate();
        }
        String description=dualGrid?popupDescription(page):paragraphs(page.has("detailLines")?lines(new JSONObject().put("lines",page.getJSONArray("detailLines"))):new ArrayList<>());
        fates.getJSONObject(cell).put("description",description);if(description.isEmpty())warning("命盘第"+(cell+1)+"格描述待补充");result.put("visitedFates",result.optInt("visitedFates")+1);
        if(dualGrid){state="code-fate-dismiss";dismissAttempts=0;return Action.tap("关闭命盘描述",.50,.97);}
        cell++;return nextCodeFate();
    }
    private Action holdAction(){int[] p=CELLS[cell];double x=gridLeft+(p[0]+.5)*gridCellWidth,y=gridTop+(p[1]+.5)*gridCellHeight;return new Action("hold","长按读取命盘 "+(cell+1)+"/12",x,y,y);}
    private Action nextFate()throws Exception{
        while(cell<12&&"catalog".equals(fates.getJSONObject(cell).optString("source")))cell++;
        if(cell<12)return holdAction();finish("");return null;
    }
    private void reuseFates()throws Exception{
        int reused=0;
        for(int i=0;i<fates.length();i++){
            JSONObject row=fates.getJSONObject(i),match=null;boolean conflict=false;
            for(int j=0;j<fateCache.length();j++){
                JSONObject cached=fateCache.getJSONObject(j);
                if(clean(row.optString("title")).equals(clean(cached.optString("title")))&&!row.optString("quality").isEmpty()&&row.optString("quality").equals(cached.optString("quality"))&&!cached.optString("description").trim().isEmpty()){
                    if(match!=null&&!clean(match.optString("description")).equals(clean(cached.optString("description"))))conflict=true;match=cached;
                }
            }
            if(match!=null&&!conflict){row.put("description",match.getString("description")).put("source","catalog");if(match.has("form"))row.put("form",match.getString("form"));reused++;}
        }
        result.put("reusedFates",reused).put("visitedFates",reused);
    }
    static List<JSONObject> lines(JSONObject page)throws Exception{
        List<JSONObject> result=new ArrayList<>();JSONArray rows=page.getJSONArray("lines");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.optString("text").trim().isEmpty())result.add(row);}
        result.sort(Comparator.comparingDouble((JSONObject r)->r.optDouble("y")).thenComparingDouble(r->r.optDouble("x")));return result;
    }
    private double cx(JSONObject row){return(row.optDouble("x")+row.optDouble("width")/2-left)/width;}
    private double cy(JSONObject row){return(row.optDouble("y")+row.optDouble("height")/2-top)/contentHeight;}
    private List<JSONObject> region(List<JSONObject> lines,double left,double y,double right,double bottom){List<JSONObject> result=new ArrayList<>();for(JSONObject row:lines)if(cx(row)>left&&cx(row)<right&&cy(row)>y&&cy(row)<bottom)result.add(row);return result;}
    private JSONObject find(List<JSONObject> lines,String regex){for(JSONObject row:lines)if(clean(row.optString("text")).matches(".*"+regex+".*"))return row;return null;}
    private boolean main(List<JSONObject> lines){List<JSONObject> lower=region(lines,0,.60,1,.81);return find(lower,"生命")!=null&&find(lower,"攻[击擊]")!=null;}
    private boolean fateHome(List<JSONObject> lines){return find(lines,"逆天[改政]命")!=null&&find(lines,"[命盈盆盘].*[郭\\p{IsHan}]")!=null&&find(lines,"开始改命")==null;}
    private boolean fateGrid(List<JSONObject> lines){
        // 新截图里提示字可能为繁体或错字；仍要求上下两个独立位置锚点。
        // 短屏按钮可贴近底边；仍需上方独立说明，不能凭浮窗中的“改命”放行。
        boolean button=find(region(lines,.2,.84,.85,1),"[开開]始[改政]命")!=null;
        boolean instruction=find(region(lines,0,.44,.7,.56),"[长長].*[宫宮官].*[详詳]情")!=null;
        boolean explanation=find(region(lines,.05,.57,.96,.72),"[改政]命.*重[置罝].*[宫宮官]格")!=null;
        return button&&(instruction||explanation);
    }
    private void locateGrid(JSONObject page)throws Exception{
        JSONObject grid=page.optJSONObject("fateGrid");
        // 缺少实际边线时只重读，不能对短屏猜测旧宫格位置。
        if(grid==null)throw new LayoutException("暂未定位命盘宫格边线");
        gridLeft=(grid.getDouble("left")-left)/width;gridTop=(grid.getDouble("top")-top)/contentHeight;
        gridCellWidth=(grid.getDouble("right")-grid.getDouble("left"))/width/4;
        gridCellHeight=(grid.getDouble("bottom")-grid.getDouble("top"))/contentHeight/4;
        gridViewport=viewportKey();
    }
    private String viewportKey(){return left+":"+top+":"+width+":"+contentHeight;}
    private void identity(JSONObject page,List<JSONObject> lines)throws Exception{
        JSONObject target=result.getJSONObject("identity");String left=(codeYuan?"":join(region(lines,0,.13,.23,.43)))+page.optString("attributeText")+page.optString("roleText")+page.optString("rarityText");
        for(String role:new String[]{"破军","龙盾","岐黄","神纪","诡道"})if(clean(left).contains(role))target.put("role",role);
        for(String rarity:new String[]{"绝密","机密","隐密","电影"})if(clean(left).contains(rarity))target.put("rarity",rarity);
        // 属性只允许专用裁图的完整值；不再从姓名、标签、职业中找单字。
        if(target.optString("attribute").isEmpty())target.put("attribute",attributeValue(page.optString("attributeText")));
    }
    static String attributeValue(String text){
        String value=clean(text).replaceAll("^[|丨]+|[|丨]+$","").replace("風","风").replace("陽","阳").replace("陰","阴");
        return value.matches("混沌|水|火|风|地|阳|阴")?value:"";
    }
    private void readNameFromFate(List<JSONObject> lines)throws Exception{
        for(JSONObject row:region(lines,.4,codeYuan?.135:.10,.85,codeYuan?.175:.18)){
            String value=clean(row.optString("text")).replaceFirst("^[^\\p{IsHan}]*命[盘盈盆]","").replaceAll("[^\\p{IsHan}·]","");
            if(value.length()>=2&&value.length()<=12&&!value.contains("命盘")&&!value.contains("命盈")){result.getJSONObject("identity").put("name",value);return;}
        }
        warning("姓名未从命盘标题识别，请在结果页补充");
    }
    private void readSkill(JSONObject page,List<JSONObject> lines,String key)throws Exception{
        List<JSONObject> body=region(lines,.20,.08,.98,.675);JSONObject title=null;
        for(JSONObject row:body){String text=clean(row.optString("text"));if(cx(row)>.34&&cx(row)<.75&&row.optDouble("height")>contentHeight*.018&&text.matches("[\\p{IsHan}·]{2,16}")){title=row;break;}}
        if(title==null){warning(label(key)+"标题未识别，将保留待补条目");result.getJSONObject("sections").getJSONArray(key).put(entry("","", ""));return;}
        List<JSONObject> description=new ArrayList<>();String form="";
        // 正文只接受独立裁框 OCR；未定位弹窗时留空，不退回整屏猜测。
        List<JSONObject> inside=popupLines(page);
        for(JSONObject row:inside)if(row.optDouble("y")>title.optDouble("y")+title.optDouble("height")){
            String text=clean(row.optString("text"));if(text.contains("详细")||text.contains("詳情"))continue;
            if(text.matches(".*等级.*|.*消耗能量.*|.*密探[1-5一二三四五]星升级.*")){if(codeYuan&&row.optDouble("y")-title.optDouble("y")>contentHeight*.08){description.add(row);continue;}form+=(form.isEmpty()?"":"；")+row.optString("text");continue;}description.add(row);
        }
        JSONObject item=entry(title.optString("text"),paragraphs(description),"");if(!form.isEmpty())item.put("form",form);
        result.getJSONObject("sections").getJSONArray(key).put(item);if(description.isEmpty())warning(label(key)+"正文未识别，可在结束后补充");
    }
    static List<JSONObject> popupLines(JSONObject page)throws Exception{return page.has("popupLines")?lines(new JSONObject().put("lines",page.getJSONArray("popupLines"))):new ArrayList<>();}
    static String popupDescription(JSONObject page)throws Exception{return paragraphs(popupLines(page));}
    private void readTalents(List<JSONObject> body)throws Exception{
        JSONObject current=null;List<JSONObject> description=new ArrayList<>();double titleY=-1;
        for(JSONObject row:body){String text=clean(row.optString("text"));double x=(row.optDouble("x")-left)/width;
            // 未解锁标题可能为灰色，不能依赖金色，否则会并入上一条正文。
            boolean title=x>.34&&x<.42&&row.optDouble("width")<width*.29&&row.optDouble("height")>=contentHeight*.015&&text.matches("[\\p{IsHan}·]{2,16}");
            if(title){
                storeTalent(current,description);current=entry(row.optString("text"),"","");titleY=row.optDouble("y");description=new ArrayList<>();
                // 解锁标签位于标题右侧，按同一行关联，不能按 OCR 返回先后猜星级。
                for(JSONObject candidate:body)if(candidate.optDouble("x")>row.optDouble("x")+row.optDouble("width")&&Math.abs(candidate.optDouble("y")-row.optDouble("y"))<row.optDouble("height")*.8){
                    String unlock=unlock(candidate.optString("text"));if(!unlock.isEmpty())current.put("unlock",unlock);
                }
            }
            else if(current!=null&&!unlock(text).isEmpty()&&row.optDouble("y")-titleY>row.optDouble("height")*1.5){
                // 灰色标题完全漏读时，下一条的解锁标签仍能截断前一条正文。
                storeTalent(current,description);current=null;description=new ArrayList<>();
            }
            else if(current!=null&&unlock(text).isEmpty()&&!text.matches(".*[星醒]解锁.*|.*觉醒.*|.*解锁"))description.add(row);
        }
        storeTalent(current,description);
    }
    private void storeTalent(JSONObject item,List<JSONObject> lines)throws Exception{
        if(item==null)return;String description=paragraphs(lines);item.put("description",description);String key=clean(item.getString("title"));JSONObject old=talents.get(key);
        // 天赋名与解锁条件用竖线分隔；缺失条件时保留原名供用户核对。
        String unlock=item.optString("unlock",old==null?"":old.optString("unlock"));item.put("unlock",unlock).put("title",item.getString("title")+(unlock.isEmpty()?"":"|"+unlock));
        if(old==null||description.length()>old.optString("description").length())talents.put(key,item);
        else if(!unlock.isEmpty()&&old.optString("unlock").isEmpty())old.put("unlock",unlock).put("title",old.getString("title")+"|"+unlock);
    }
    static String unlock(String raw){
        String text=clean(raw).replace('鎖','锁');java.util.regex.Matcher match=java.util.regex.Pattern.compile("([1-5一二三四五])星.*解锁").matcher(text);
        if(match.find()){String n=match.group(1);int index="一二三四五".indexOf(n);return "密探"+(index>=0?n:String.valueOf("一二三四五".charAt(Integer.parseInt(n)-1)))+"星解锁";}
        return text.matches(".*[觉覺]醒.*解锁.*")?"密探觉醒后解锁":"";
    }
    private void readGrid(List<JSONObject> lines)throws Exception{
        // 名称分格与长按共享本帧网格，避免短屏首行漏读、相邻格串行。
        for(int i=0;i<CELLS.length;i++){int[] p=CELLS[i];double x=gridLeft+p[0]*gridCellWidth,y=gridTop+p[1]*gridCellHeight;List<JSONObject> cell=region(lines,x+.004,y+.003,Math.min(1,x+gridCellWidth-.004),y+gridCellHeight-.003);List<JSONObject> words=new ArrayList<>();Map<String,Integer> votes=new LinkedHashMap<>();
            for(JSONObject row:cell){String text=clean(row.optString("text"));if(text.matches(".*解锁.*|生效中|[0-9]+|[+十]"))continue;words.add(row);String ink=row.optString("ink");if(!ink.isEmpty())votes.put(ink,votes.getOrDefault(ink,0)+1);}
            String title=join(words).replaceAll("[\\s|「」]","");String quality="";int best=0;for(Map.Entry<String,Integer> vote:votes.entrySet())if(vote.getValue()>best){quality=vote.getKey();best=vote.getValue();}
            fates.put(entry(title,"",quality).put("cell",i+1));if(title.isEmpty())warning("命盘第"+(i+1)+"格名称待补充");if(quality.isEmpty())warning("命盘第"+(i+1)+"格品质待核对");
        }
    }
    static boolean stillPixels(String current,String previous){
        if(current.isEmpty()||previous.isEmpty())return false;
        try{byte[] a=android.util.Base64.decode(current,0),b=android.util.Base64.decode(previous,0);if(a.length!=b.length||a.length<100)return false;long difference=0;for(int i=0;i<a.length;i++)difference+=Math.abs((a[i]&255)-(b[i]&255));return (double)difference/a.length<1.8;}catch(Exception e){return false;}
    }
    /** 半透明弹窗后的动画会改变像素；文字位置稳定也可确认没有继续滚动。 */
    static boolean stationaryRows(List<JSONObject> current,List<JSONObject> previous){
        if(current.size()<5||previous.size()<5)return false;int matched=0;boolean[] used=new boolean[previous.size()];
        for(JSONObject row:current)for(int i=0;i<previous.size();i++){JSONObject old=previous.get(i);double h=Math.max(row.optDouble("height"),old.optDouble("height"));
            if(!used[i]&&Math.abs(row.optDouble("y")-old.optDouble("y"))<h*.3&&Math.abs(row.optDouble("x")-old.optDouble("x"))<h&&similar(clean(row.optString("text")),clean(old.optString("text")))>.65){used[i]=true;matched++;break;}
        }return matched>=Math.max(current.size(),previous.size())*.8;
    }
    /** 视觉折行不等于段落：正常行距拼接，明显段间距或编号列表才另起一段。 */
    static String paragraphs(List<JSONObject> rows){
        StringBuilder out=new StringBuilder();JSONObject previous=null;
        for(JSONObject row:rows){String text=row.optString("text").trim();if(text.isEmpty())continue;
            if(previous!=null){double h=Math.max(row.optDouble("height"),previous.optDouble("height")),gap=row.optDouble("y")-previous.optDouble("y")-previous.optDouble("height");
                boolean separate=gap>h*.95||gap>h*.15&&text.matches("^(?:[①②③④⑤⑥]|[1-9][、.]|[（(][一二三四五1-9][）)]).*");
                if(separate)out.append('\n');else if(out.length()>0&&Character.isLetterOrDigit(out.charAt(out.length()-1))&&out.charAt(out.length()-1)<128&&text.charAt(0)<128&&Character.isLetter(text.charAt(0)))out.append(' ');
            }out.append(text);previous=row;
        }return out.toString();
    }
    private JSONObject entry(String title,String description,String quality)throws Exception{return new JSONObject().put("title",title).put("description",description).put("quality",quality);}
    static String clean(String s){return s.replaceAll("\\s","").replace('長','长').replace('賦','赋').replace('盤','盘').replace('軍','军').replace('龍','龙').replace('紀','纪').replace('詭','诡');}
    static String join(List<JSONObject> lines){StringBuilder out=new StringBuilder();double y=-999;for(JSONObject row:lines){if(out.length()>0&&Math.abs(row.optDouble("y")-y)>row.optDouble("height")*.5)out.append('\n');out.append(row.optString("text"));y=row.optDouble("y");}return out.toString().trim();}
    static double similar(String a,String b){if(a.equals(b))return 1;if(a.isEmpty()||b.isEmpty())return 0;int[] dp=new int[b.length()+1];for(int j=0;j<dp.length;j++)dp[j]=j;for(int i=1;i<=a.length();i++){int prev=dp[0];dp[0]=i;for(int j=1;j<=b.length();j++){int old=dp[j];dp[j]=Math.min(Math.min(dp[j]+1,dp[j-1]+1),prev+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=old;}}return 1-(double)dp[b.length()]/Math.max(a.length(),b.length());}
}
