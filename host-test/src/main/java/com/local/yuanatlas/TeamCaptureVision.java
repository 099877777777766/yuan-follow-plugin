package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 视频对应配队布局的独立识别器；坐标先扣除黑边，再用于 OCR 和手势。 */
final class TeamCaptureVision {
    static JSONObject read(Bitmap source,Rect overlay,String stage,JSONArray agents)throws Exception{
        return read(source,overlay,stage,agents,"");
    }
    static JSONObject read(Bitmap source,Rect overlay,String stage,JSONArray agents,String currentName)throws Exception{
        return read(source,overlay,stage,agents,currentName,false);
    }
    static JSONObject read(Bitmap source,Rect overlay,String stage,JSONArray agents,String currentName,boolean codeYuan)throws Exception{
        return read(source,overlay,stage,agents,currentName,codeYuan,null);
    }
    static JSONObject read(Bitmap source,Rect overlay,String stage,JSONArray agents,String currentName,boolean codeYuan,Rect sessionBounds)throws Exception{
        // 平板左右黑边只裁识别副本；页面坐标还原后供预览和手势使用。
        try(GameViewport viewport=GameViewport.horizontal(source)){
            if(viewport.image!=source){
                Rect retained=sessionBounds!=null&&sessionBounds.left==viewport.bounds.left&&sessionBounds.right==viewport.bounds.right?viewport.local(sessionBounds):null;
                return viewport.restore(read(viewport.image,viewport.local(overlay),stage,agents,currentName,codeYuan,retained));
            }
        }
        // 同一采集会话沿用立绘入口的游戏边界，弹窗遮罩和命盘深色背景不应改变坐标系。
        Rect bounds=sessionBounds!=null?sessionBounds:(codeYuan?codeYuanContent(source):RuYuanVision.content(source));Bitmap image=CaptureOcrImage.excluding(source,overlay);
        try(TextRecognizer reader=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
            // 页面坐标始终保留真实游戏边界；局部控件可以有自己的、延伸到屏幕外的设计坐标系。
            Rect fateArea=bounds,detailArea=bounds,grid=null;
            if(codeYuan&&(stage.equals("fate")||stage.equals("stars"))){
                grid=RuYuanFateGrid.locate(image,bounds,true);
                if(grid!=null){double h=grid.height()/.454;int t=(int)Math.round(grid.top-.2135*h);fateArea=new Rect(0,t,image.getWidth(),t+(int)Math.round(h));}
            }
            JSONArray rows=ocr(reader,image,bounds,0,0,1,1,2);
            if(codeYuan&&stage.equals("details")){
                for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String label=TeamCaptureSession.clean(row.getString("text"));
                    if(label.startsWith("属性")&&row.getDouble("x")<source.getWidth()*.2&&row.getDouble("y")>source.getHeight()*.15&&row.getDouble("y")<source.getHeight()*.4){
                        double height=source.getWidth()/ .47;int top=(int)Math.round(row.getDouble("y")+row.getDouble("height")/2-height*.222);
                        // 短屏上设计原点在屏幕上方，截断原点会让生命蝶读到上一行攻击蝶。
                        detailArea=new Rect(0,top,source.getWidth(),(int)Math.round(top+height));break;
                    }
                }
            }
            JSONObject page=new JSONObject().put("width",source.getWidth()).put("height",source.getHeight()).put("top",bounds.top).put("contentHeight",bounds.height()).put("lines",rows);
            if(stage.equals("main")||stage.equals("next")){
                String nameText=crop(reader,image,bounds,.09,.12,.16,.27);
                page.put("teamNameText",nameText);
                page.put("teamName",matchName(nameText,agents));
                detailsAnchor(reader,image,bounds,rows,page);
                if(codeYuan){
                    // 无黑边布局中100的首位比旧框更靠左；完整数字区留足边距，00不能当作等级。
                    detailValue(reader,image,bounds,page,"teamLevel",.085,.865,.18,.065,false);
                }else page.put("teamLevel",crop(reader,image,bounds,.125,.863,.11,.047));
                String rank=crop(reader,image,bounds,.38,.87,.073,.033);
                if(codeYuan){String anchored=rankFromRows(rows,image,bounds);if(!anchored.isEmpty())rank=anchored;}
                if(!rank.matches("[0-9]{1,2}"))rank=TeamCaptureSession.region(page,.32,.855,.47,.915).replaceAll("[^0-9]","");
                page.put("teamRank",rank);
                if(codeYuan){
                    // 修为的加号和双位数字一并保留边距，避免+11的第一笔被窄框截断。
                    if(!rank.matches("[1-9][0-9]?")){
                        // 连同“修为”标签保留上下文，细窄的连续数字1单独成行容易被OCR忽略。
                        String ink=crop(reader,image,bounds,.32,.81,.15,.115).replace("修","").replace("为","");
                        if(!ink.matches("[+＋]?[1-9][0-9]?"))ink=rankInk(reader,image,bounds);
                        page.put("teamRankText",ink);
                        Matcher digits=Pattern.compile("^[+＋]?([1-9][0-9]?)$").matcher(ink);
                        if(digits.matches())page.put("teamRank",digits.group(1));
                    }
                    detailsAnchor(reader,image,bounds,rows,page);
                    // 立绘总属性作为入口数据保留，详情页能读到时再用详情页完整数值更新。
                    detailValue(reader,image,bounds,page,"teamLife",.18,.70,.16,.034,false);
                    detailValue(reader,image,bounds,page,"teamAttack",.18,.73,.16,.034,false);
                    mainAttributes(rows,page);
                }
            }
            // 点击未生效后仍在立绘页时，重新定位同一按钮，不新建密探。
            if(codeYuan&&stage.equals("details"))detailsAnchor(reader,image,bounds,rows,page);
            if(stage.equals("details")){
                // 右对齐数字必须从整段数值区读取。旧窄框会把8425截成425、2600/2600截成00/2600，
                // 二者仍是合法数字，不能靠“格式正确就不回退”判断完整性。左右留白同时兼容短值和满蝶。
                detailValue(reader,image,detailArea,page,"teamLife",.25,codeYuan?.312:.303,.18,codeYuan?.030:.042,false);
                detailValue(reader,image,detailArea,page,"teamAttack",.56,codeYuan?.312:.303,.18,codeYuan?.030:.042,false);
                detailValue(reader,image,detailArea,page,"teamButterflyAttack",.70,.607,.24,.042,true);
                detailValue(reader,image,detailArea,page,"teamButterflyLife",.70,.642,.24,.042,true);
                if(codeYuan&&!detailArea.equals(bounds))page.put("teamDetailsCloseX",.93).put("teamDetailsCloseY",(detailArea.top+.23*detailArea.height()-bounds.top)/bounds.height());
            }
            if(stage.equals("evolution")){
                // 星与节点分别取图标中心，不把下方“生命/攻击 + 数值”当成化极等级。
                // 录屏与实机的培养区横向比例不同，以第一颗金星为锚点同步定位星与节点，不能套用旧录屏横坐标。
                double scale=evolutionScale(image,bounds);
                double starY=.724,upperY=.788,lowerY=.814;
                if(codeYuan){
                    // 培养区按屏幕宽度缩放；先定位独立金星，再以宽度换算节点距离，避免长屏纵向比例漂移。
                    double[] anchor=evolutionAnchor(image,bounds);
                    if(anchor!=null){scale=anchor[0]/.366;starY=anchor[1];upperY=starY+image.getWidth()*.134/bounds.height();lowerY=starY+image.getWidth()*.190/bounds.height();}
                }
                int stars=litSequence(image,bounds,scaled(new double[]{.366,.445,.522,.602,.679},scale),new double[]{starY,starY,starY,starY,starY});
                int nodes=litSequence(image,bounds,scaled(new double[]{.273,.399,.524,.650,.776},scale),new double[]{lowerY,upperY,lowerY,upperY,lowerY});
                if(stars>=1&&nodes>=0)page.put("teamEvolution",(stars-1)*6+nodes);
                // 五化极时改为左右升星预览：左侧是当前星数，右侧多一星，不能把右侧当作已拥有。
                int current=evolutionPreviewStars(image,bounds,codeYuan);
                if(current>=1){page.put("teamEvolution",(current-1)*6+5);page.put("teamEvolutionPreview",true);}
                // 觉醒后和五星待觉醒不再显示普通节点；文字与各自位置的五颗金星要同时成立。
                String awakening=TeamCaptureSession.region(page,.20,.68,.86,.87);
                if(!awakening.contains("已觉醒")&&!awakening.contains("待觉醒"))awakening=crop(reader,image,bounds,.20,.68,.66,.19);
                int awakenedStars=litSequence(image,bounds,new double[]{.350,.425,.500,.575,.650},new double[]{.808,.808,.808,.808,.808});
                int pendingStars=litSequence(image,bounds,new double[]{.278,.354,.427,.503,.578},new double[]{.741,.741,.741,.741,.741});
                // 页面定位与具体档位分开：星数读得出但节点/状态读不出时，仍可安全返回继续采集。
                if(stars>=1||current>=1||awakenedStars==5||pendingStars==5)page.put("teamEvolutionPage",true);
                if(awakening.contains("已觉醒")&&awakenedStars==5){page.put("teamEvolution",30).put("teamAwakeningPage",true);}
                else if(awakening.contains("待觉醒")&&pendingStars==5){page.put("teamEvolution",29).put("teamAwakeningPage",true);}
            }
            if(stage.equals("fate")||stage.equals("stars")){
                // 已在入口确定身份就直接沿用；星石逐格读取完全不再做姓名 OCR。
                String fateName=currentName;
                // 只有入口缺名才尝试更清楚的命盘横排标题；已有身份绝不二次比对。
                if(stage.equals("fate")&&fateName.isEmpty()){
                    fateName=matchName(linesIn(rows,box(image,fateArea,.55,.115,.36,.055)),agents);
                    if(fateName.isEmpty())fateName=matchName(crop(reader,image,fateArea,.55,.125,.34,.045),agents);
                }
                page.put("teamFateName",fateName);
                // 没有可靠网格时交由状态机重读，不能使用旧屏幕比例拼出十二个错误格。
                if(codeYuan&&grid==null)return page;
                if(grid!=null){
                    JSONArray targets=new JSONArray();
                    for(int[] cell:RuYuanAutoCapture.CELLS)targets.put(new JSONObject().put("x",(grid.left+(cell[0]+.5)*grid.width()/4)/image.getWidth()).put("y",(grid.top+(cell[1]+.5)*grid.height()/4-bounds.top)/bounds.height()));
                    page.put("teamCellTargets",targets);
                }
                if(stage.equals("fate")){
                    JSONArray cells=new JSONArray();
                    for(int[] cell:RuYuanAutoCapture.CELLS){double x=(codeYuan?.138:.145)+cell[0]*(codeYuan?.24:.253),y=(codeYuan?.270:.267)+cell[1]*(codeYuan?.113:.118);String content=crop(reader,image,fateArea,x-.12,y-.043,.24,.105);
                        String title=content.replace("生效中","").replaceAll("[^\\p{IsHan}0-9+·]","");
                        // 整屏与逐格 OCR 按完整效果匹配；已绑定身份才允许已验证的整词纠错。
                        String whole=linesIn(rows,box(image,fateArea,x-.12,y-.035,.24,.063));
                        String matched=matchFate(whole,title,agents,fateName);
                        if(matched.isEmpty()&&content.contains("生效中")){
                            // 星石图标和“生效中”会干扰中间的短标题，按文字带单独重读再做完整名称校验。
                            String label=crop(reader,image,fateArea,x-.115,y-.018,.23,.047);
                            matched=matchFate(label,label,agents,fateName);if(title.isEmpty())title=label;
                        }
                        if(!matched.isEmpty())title=matched;
                        // 未解锁组显示“可解锁”或“达到修为”，不能把锁定项当作漏识别的生效项。
                        String lockText=fateKey(content);
                        boolean locked=lockText.contains("可解锁")||lockText.contains("达到修为")||lockText.contains("信赖值达到");
                        cells.put(new JSONObject().put("active",content.contains("生效中")).put("locked",locked).put("title",title));
                    }
                    if(fateName.isEmpty()){
                        // 稀有姓氏可能误读；至少两条各自独属于同一密探的完整命盘才补全身份。
                        fateName=fateIdentity(cells,agents);page.put("teamFateName",fateName);
                        if(!fateName.isEmpty())for(int n=0;n<cells.length();n++){JSONObject cell=cells.getJSONObject(n);String matched=matchFate(cell.optString("title"),cell.optString("title"),agents,fateName);if(!matched.isEmpty())cell.put("title",matched);}
                    }
                    page.put("teamCells",cells);
                }
                if(stage.equals("stars")){
                    page.put("teamSelectedCell",selectedCell(image,fateArea,codeYuan));
                    page.put("teamMainStar",star(reader,image,fateArea,false,codeYuan));page.put("teamAuxiliaryStar",star(reader,image,fateArea,true,codeYuan));
                }
            }
            return page;
        }finally{if(image!=source)image.recycle();}
    }
    private static void detailsAnchor(TextRecognizer reader,Bitmap image,Rect bounds,JSONArray rows,JSONObject page)throws Exception{
        // 文字用于定位，实际点击上方圆形图标中心；文字标签本身不保证响应点击。
                    // 代号鸢的信印/圣物按钮会推动详情入口，按当前帧文字定位，不能使用如鸢的固定高度。
                    for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);double x=(row.getDouble("x")+row.getDouble("width")/2)/image.getWidth(),y=(row.getDouble("y")+row.getDouble("height")/2-bounds.top)/bounds.height();
                        String label=TeamCaptureSession.clean(row.getString("text")).replaceFirst("^[^\\p{IsHan}]+","");
                        if(label.startsWith("详情")&&x<.25&&y>.5&&y<.78){
                            // 合并行取开头“详情”的中心，而非整行中心，避免点到圣物图鉴。
                            x=(row.getDouble("x")+row.getDouble("width")/label.length())/image.getWidth();page.put("teamDetailsX",x).put("teamDetailsY",(row.getDouble("y")-row.getDouble("height")*1.5-bounds.top)/bounds.height());
                        }
                    }
                    if(!page.has("teamDetailsX")){
                        // “详情”和右侧“圣物图鉴”可能被整屏OCR合并成一行，局部重读只保留详情列。
                        JSONArray detailRows=ocr(reader,image,bounds,.04,.56,.13,.17,4);
                        for(int i=0;i<detailRows.length();i++){JSONObject row=detailRows.getJSONObject(i);if(TeamCaptureSession.clean(row.getString("text")).equals("详情"))page.put("teamDetailsX",(row.getDouble("x")+row.getDouble("width")/2)/image.getWidth()).put("teamDetailsY",(row.getDouble("y")-row.getDouble("height")*1.5-bounds.top)/bounds.height());}
                    }
    }
    private static String nameKey(String text){return TeamCaptureSession.clean(text).replace('鄷','酆').replaceAll("[^\\p{IsHan}·]","");}
    private static String matchName(String text,JSONArray agents)throws Exception{String found="",clean=nameKey(text);for(int i=0;i<agents.length();i++){String name=agents.getJSONObject(i).getString("name"),key=nameKey(name);if(!key.isEmpty()&&clean.contains(key)){if(!found.isEmpty()&&!found.equals(name))return "";found=name;}}return found;}
    private static String linesIn(JSONArray rows,Rect area)throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);double x=row.getDouble("x")+row.getDouble("width")/2,y=row.getDouble("y")+row.getDouble("height")/2;if(x>=area.left&&x<=area.right&&y>=area.top&&y<=area.bottom)text.append(row.getString("text"));}return TeamCaptureSession.clean(text.toString());
    }
    private static String rankFromRows(JSONArray rows,Bitmap image,Rect bounds)throws Exception{
        String found="";for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);double x=(row.getDouble("x")+row.getDouble("width")/2)/image.getWidth(),y=(row.getDouble("y")+row.getDouble("height")/2-bounds.top)/bounds.height();
            // 必须是修为列里的独立“+数字”行，不能拼接竖排“修为”中的误读笔画形成117。
            if(x<.30||x>.48||y<.78||y>.95||row.getDouble("height")>image.getWidth()*.1)continue;
            Matcher m=Pattern.compile("^[+＋]([1-9][0-9]?)$").matcher(TeamCaptureSession.clean(row.getString("text")));
            if(m.matches()){if(!found.isEmpty()&&!found.equals(m.group(1)))return "";found=m.group(1);}
        }return found;
    }
    /** 立绘属性带的标签和数字同处一行，可独立于屏幕高宽比确认完整数值。 */
    private static void mainAttributes(JSONArray rows,JSONObject page)throws Exception{
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optDouble("x")>page.getInt("width")*.25)continue;
            Matcher m=Pattern.compile("^(生命|攻击)([1-9][0-9]{0,8})$").matcher(TeamCaptureSession.clean(row.getString("text")).replaceAll("[^\\p{IsHan}0-9]",""));
            if(m.matches())page.put(m.group(1).equals("生命")?"teamLife":"teamAttack",m.group(2));
            String label=TeamCaptureSession.clean(row.getString("text")).replaceAll("[^\\p{IsHan}]","");
            if(label.equals("生命")||label.equals("攻击")){
                String found="";double y=row.getDouble("y")+row.getDouble("height")/2;
                for(int n=0;n<rows.length();n++){JSONObject value=rows.getJSONObject(n);String digits=TeamCaptureSession.clean(value.getString("text"));
                    if(value.getDouble("x")>=row.getDouble("x")+row.getDouble("width")&&value.getDouble("x")+value.getDouble("width")<page.getInt("width")*.36&&Math.abs(value.getDouble("y")+value.getDouble("height")/2-y)<row.getDouble("height")*.65&&digits.matches("[1-9][0-9]{0,8}")){if(!found.isEmpty()){found="";break;}found=digits;}
                }if(!found.isEmpty())page.put(label.equals("生命")?"teamLife":"teamAttack",found);
            }
        }
    }
    private static void detailValue(TextRecognizer reader,Bitmap image,Rect bounds,JSONObject page,String key,double x,double y,double w,double h,boolean fraction)throws Exception{
        // 游戏不会给当前值补前导零；00/500 表示漏字，必须回退，只有单个 0 才是真正零蝶。
        String pattern=fraction?"(?<![0-9])(?:0|[1-9][0-9]{0,4})[/／][1-9][0-9]{0,4}(?![0-9])":"(?<![0-9])(?:0|[1-9][0-9]{0,8})(?![0-9])";
        String value=crop(reader,image,bounds,x,y,w,h);
        String found=singleNumber(value,pattern);
        // 低清录屏宽框可能连到下一条进度栏；只在失败时收窄同一数字行，不把02600猜成0/2600。
        if(found==null&&fraction)found=singleNumber(crop(reader,image,bounds,x,y,w,h*.65),pattern);
        if(found==null)found=singleNumber(linesIn(page.getJSONArray("lines"),box(image,bounds,x,y,w,h)),pattern);
        if(found!=null)page.put(key,found);
    }
    private static String singleNumber(String value,String pattern){Matcher m=Pattern.compile(pattern).matcher(value);if(!m.find())return null;String found=m.group();return m.find()?null:found;}
    static String matchFate(String whole,String cropped,JSONArray agents,String name)throws Exception{
        // 标点不参与完整效果名称匹配；“回应吾！”和“普攻强化，…”不能因OCR省略标点丢失归属。
        whole=fateKey(whole);cropped=fateKey(cropped);
        // 原词精确匹配优先；纠错必须在当前密探图鉴中完整唯一命中，不能拿误读词猜姓名。
        for(int pass=0;pass<(name.isEmpty()?1:2);pass++){
            String a=pass==0?whole:fateReadKey(whole,name),b=pass==0?cropped:fateReadKey(cropped,name),found="";
            for(int i=0;i<agents.length();i++){JSONObject agent=agents.getJSONObject(i);if(!name.isEmpty()&&!agent.optString("name").equals(name))continue;JSONArray fates=agent.optJSONArray("fates");if(fates==null)continue;
                for(int n=0;n<fates.length();n++){String title=fates.getString(n),effect=fateKey(title.replaceFirst("^(?:丹曲|帝君|益算|上生|太岁|阳明|延寿|中天|暗星|命宫|天关|北极|阴阳)\\s+",""));if(effect.equals(a)||effect.equals(b)){if(!found.isEmpty()&&!found.equals(title))return "";found=title;}}
            }
            if(!found.isEmpty())return found;
        }return "";
    }
    private static String fateKey(String value){return TeamCaptureSession.clean(value).replaceAll("[^\\p{IsHan}0-9+·]","");}
    private static String fateEffect(String value){return fateKey(value.replaceFirst("^(?:丹曲|帝君|益算|上生|太岁|阳明|延寿|中天|暗星|命宫|天关|北极|阴阳)\\s+",""));}
    private static String fateIdentity(JSONArray cells,JSONArray agents)throws Exception{
        String candidate="";java.util.Set<String> evidence=new java.util.HashSet<>();
        for(int n=0;n<cells.length();n++){
            String effect=fateEffect(cells.getJSONObject(n).optString("title"));if(effect.isEmpty())continue;
            String owner="";boolean ambiguous=false;
            for(int a=0;a<agents.length();a++){JSONObject agent=agents.getJSONObject(a);JSONArray fates=agent.optJSONArray("fates");if(fates==null)continue;
                for(int f=0;f<fates.length();f++)if(effect.equals(fateEffect(fates.getString(f)))){String name=agent.getString("name");if(!owner.isEmpty()&&!owner.equals(name))ambiguous=true;owner=name;break;}
            }
            if(ambiguous||owner.isEmpty())continue;
            if(!candidate.isEmpty()&&!candidate.equals(owner))return "";
            candidate=owner;evidence.add(effect);
        }
        return evidence.size()>=2?candidate:"";
    }
    private static String fateReadKey(String value,String name){
        String key=fateKey(value);
        // 用户原图中整屏读成“刚骨离髓”、局部读成“刚骨高髓”。限定已绑定的满宠与完整四字，
        // 仍须在他的图鉴中唯一命中；不逐字替换“刚/高”，也不由误读词猜测密探身份。
        if(name.equals("满宠")&&(key.equals("刚骨离髓")||key.equals("刚骨高髓")))return "剐骨离髓";
        // 原图及局部增强均把儆读为做；这里只替换完整词，仍由当前密探自己的图鉴约束。
        if(!name.isEmpty()&&key.equals("做戒"))return "儆戒";
        if(nameKey(name).equals("酆公珠")&&key.equals("龙蛇之垫"))return "龙蛇之蛰";
        return key;
    }
    private static Rect codeYuanContent(Bitmap image){
        // 弹窗遮罩会把游戏背景压暗到18以下，不能沿用亮度18的留边阈值，否则每名密探裁剪高度不同。
        int top=0,bottom=image.getHeight();
        while(top<image.getHeight()/6&&pureBlackRow(image,top))top++;
        while(bottom>image.getHeight()*5/6&&pureBlackRow(image,bottom-1))bottom--;
        return new Rect(0,top,image.getWidth(),bottom);
    }
    private static boolean pureBlackRow(Bitmap image,int y){int total=0,visible=0;for(int x=0;x<image.getWidth();x+=8){int c=image.getPixel(x,y);total++;if(Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)))>4)visible++;}return visible<total*.08;}
    private static String rankInk(TextRecognizer reader,Bitmap source,Rect bounds)throws Exception{
        // 低对比度的浅绿色+11在人物背景上容易丢失，按颜色提取字迹，仍交由正式OCR读取。
        Rect r=box(source,bounds,.32,.872,.15,.042);Bitmap mask=Bitmap.createBitmap(r.width()+16,r.height()+16,Bitmap.Config.ARGB_8888);mask.eraseColor(Color.WHITE);float[] hsv=new float[3];
        try{for(int y=0;y<r.height();y++)for(int x=0;x<r.width();x++){
            Color.colorToHSV(source.getPixel(r.left+x,r.top+y),hsv);
            if(hsv[0]>25&&hsv[0]<105&&hsv[1]>.07&&hsv[2]>.55)mask.setPixel(x+8,y+8,Color.BLACK);
        }return crop(reader,mask,new Rect(0,0,mask.getWidth(),mask.getHeight()),0,0,1,1);}finally{mask.recycle();}
    }
    private static JSONObject star(TextRecognizer r,Bitmap b,Rect bounds,boolean auxiliary,boolean codeYuan)throws Exception{
        double x=auxiliary?.67:.34;String text=crop(r,b,bounds,x,codeYuan?.753:.766,.22,codeYuan?.10:.085);JSONObject result=new JSONObject();
        String names=auxiliary?"右弼 天马 擎羊 三台 文曲 解神 天钺 左辅 天巫 文昌 天魁 禄存 红鸾 天刑 恩光 天贵 地空 火星 铃星 天姚 陀螺 阴煞 八座 地劫":"天府 天相 巨门 太阳 廉贞 太阴 紫微 七杀 天机 武曲 破军 天同 天梁 贪狼";
        for(String name:names.split(" "))if(text.contains(name)){if(result.has("name"))return new JSONObject();result.put("name",name);}
        if(!result.has("name")){
            String nameText=crop(r,b,bounds,auxiliary?.69:.38,.827,.17,.022);
            for(String name:names.split(" "))if(nameText.equals(name)){result.put("name",name);break;}
        }
        Matcher level=Pattern.compile("(?<!\\d)([1-5]?\\d|60)级").matcher(text);if(level.find()&&Integer.parseInt(level.group(1))>0){
            int value=Integer.parseInt(level.group(1));
            // 保留画面已显示的20级等数值；突破未知单独标记，不能把已读等级降回默认1级。
            result.put("level",level.group(1)+(text.contains("突破")?" 突破":""));
            if(value<60&&value%10==0&&!text.contains("突破"))result.put("breakthroughUnknown",true);
        }
        // 明确的加号、锁图标才表示空槽；识别不到名字不等同于没有装备。
        if(!result.has("name")&&!text.matches(".*[0-9]+级.*")&&(text.equals("+")||text.equals("未解锁")||emptyIcon(b,bounds,auxiliary?(codeYuan?.71:.75):(codeYuan?.43:.45),codeYuan)))return new JSONObject().put("name","").put("level","1");
        return result;
    }
    private static boolean emptyIcon(Bitmap b,Rect area,double x,boolean codeYuan){
        // 空槽中间为白色加号或白锁，左右背景均为低饱和深色；金/紫星石不满足。
        int bright=0,dark=0,colored=0,total=0;
        Rect r=box(b,area,x-.041,codeYuan?.770:.784,.082,.043);float[] hsv=new float[3];
        for(int yy=r.top;yy<r.bottom;yy++)for(int xx=r.left;xx<r.right;xx++){Color.colorToHSV(b.getPixel(xx,yy),hsv);total++;if(hsv[1]<(codeYuan?.35:.2)&&hsv[2]>.8)bright++;if(hsv[2]<.65)dark++;if(hsv[1]>.3&&hsv[2]>.65)colored++;}
        return bright>total*(codeYuan?.06:.12)&&dark>total*.25&&colored<total*.04;
    }
    static int selectedCell(Bitmap b,Rect bounds){
        return selectedCell(b,bounds,false);
    }
    static int selectedCell(Bitmap b,Rect bounds,boolean codeYuan){
        int best=-1;double score=0,runner=0;
        for(int i=0;i<12;i++){
            int[] c=RuYuanAutoCapture.CELLS[i];double x=.018+c[0]*.24,y=(codeYuan?.2135:.203)+c[1]*(codeYuan?.1135:.118);
            double next=edge(b,bounds,x,y,.225)+edge(b,bounds,x,y+(codeYuan?.1135:.116),.225);
            // 代号鸢生效按钮会覆盖下边框；同时看两条竖边，避免仅剩上边时与上一格同分。
            if(codeYuan)next+=verticalEdge(b,bounds,x,y,.1135)+verticalEdge(b,bounds,x+.24,y,.1135);
            if(next>score){runner=score;best=i;score=next;}else runner=Math.max(runner,next);
        }
        // 相邻格共享一条边，必须有足够亮度且领先次高分，不能把普通金色网格当成选中框。
        return score>=(codeYuan?1.25:.75)&&score-runner>=.25?best:-1;
    }
    private static double verticalEdge(Bitmap b,Rect r,double x,double y,double h){
        double best=0;float[] hsv=new float[3];int center=(int)(x*b.getWidth()),radius=Math.max(3,(int)(b.getWidth()*.006));
        for(int xx=Math.max(0,center-radius);xx<=Math.min(b.getWidth()-1,center+radius);xx++){
            int hit=0,total=0;for(int n=12;n<88;n++){int yy=r.top+(int)((y+h*n/100)*r.height());if(yy<r.top||yy>=r.bottom)continue;total++;Color.colorToHSV(b.getPixel(xx,yy),hsv);if(hsv[0]>25&&hsv[0]<70&&hsv[1]>.08&&hsv[2]>.82)hit++;}
            if(total>0)best=Math.max(best,hit/(double)total);
        }return best;
    }
    private static double edge(Bitmap b,Rect r,double x,double y,double w){
        double best=0;float[] hsv=new float[3];
        // 分辨率缩放和动画会让边框偏离单像素坐标，在附近窄带逐行寻找连续高亮。
        int center=r.top+(int)(y*r.height()),radius=Math.max(3,(int)(r.height()*.006));
        for(int yy=Math.max(r.top,center-radius);yy<=Math.min(r.bottom-1,center+radius);yy++){
            int hit=0,total=0;
            for(int n=12;n<88;n++){
                int xx=(int)((x+w*n/100)*b.getWidth());if(xx<0||xx>=b.getWidth())continue;total++;
                Color.colorToHSV(b.getPixel(xx,yy),hsv);if(hsv[0]>25&&hsv[0]<70&&hsv[1]>.08&&hsv[2]>.82)hit++;
            }
            if(total>0)best=Math.max(best,hit/(double)total);
        }
        return best;
    }
    private static int litSequence(Bitmap b,Rect r,double[] xs,double[] ys){int count=0;boolean dark=false;for(int i=0;i<xs.length;i++){boolean lit=gold(b,r,xs[i],ys[i]);if(lit&&dark)return -1;if(lit)count++;else dark=true;}return count;}
    private static double[] scaled(double[] xs,double scale){for(int i=0;i<xs.length;i++)xs[i]*=scale;return xs;}
    private static double[] evolutionAnchor(Bitmap image,Rect bounds){
        double[] found=null;
        for(double[] anchor:evolutionAnchors(image,bounds,.31,.60,.10,.20))if(found==null||anchor[2]>found[2])found=anchor;
        return found;
    }
    /** 先验证完整左右星行，再决定锚点；立绘金色弧线可能比第一颗星的连通块更大。 */
    private static int evolutionPreviewStars(Bitmap image,Rect bounds,boolean codeYuan){
        java.util.List<double[]> anchors=codeYuan?evolutionAnchors(image,bounds,.10,.60,.08,.20):new java.util.ArrayList<>();
        // 保留旧版如鸢的固定布局候选；任何候选都必须满足连续点亮及右侧恰好多一星。
        anchors.add(new double[]{.139,.723,0});int found=0;
        for(double[] anchor:anchors){
            double y=anchor[1];double[] ys={y,y,y,y,y};
            int current=litSequence(image,bounds,new double[]{.139,.213,.288,.363,.437},ys);
            int next=litSequence(image,bounds,new double[]{.552,.627,.701,.776,.850},ys);
            if(current>=1&&current<5&&next==current+1){
                // 多条候选行若给出冲突档位，不确认升星预览，不用图案面积决定当前星级。
                if(found!=0&&found!=current)return 0;
                found=current;
            }
        }
        return found;
    }
    private static java.util.List<double[]> evolutionAnchors(Bitmap image,Rect bounds,double x0,double y0,double width,double height){
        Rect r=box(image,bounds,x0,y0,width,height);int w=r.width(),h=r.height();boolean[] ink=new boolean[w*h];float[] hsv=new float[3];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){Color.colorToHSV(image.getPixel(r.left+x,r.top+y),hsv);ink[y*w+x]=hsv[0]>25&&hsv[0]<70&&hsv[1]>.25&&hsv[2]>.65;}
        int[] queue=new int[ink.length];java.util.List<double[]> found=new java.util.ArrayList<>();
        // 先筛出星形尺寸的候选；弧线局部仍可能满足尺寸，预览还须校验完整左右星行。
        for(int start=0;start<ink.length;start++)if(ink[start]){
            int head=0,tail=1,minX=w,minY=h,maxX=0,maxY=0;queue[0]=start;ink[start]=false;
            while(head<tail){int p=queue[head++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx>=0&&nx<w&&ny>=0&&ny<h&&ink[ny*w+nx]){ink[ny*w+nx]=false;queue[tail++]=ny*w+nx;}}
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;
            if(bw>image.getWidth()*.025&&bh>image.getWidth()*.025&&bw<image.getWidth()*.08&&bh<image.getWidth()*.09){
                found.add(new double[]{(r.left+(minX+maxX)/2.0)/image.getWidth(),(r.top+(minY+maxY)/2.0-bounds.top)/bounds.height(),tail});
            }
        }return found;
    }
    private static double evolutionScale(Bitmap b,Rect area){
        Rect r=box(b,area,.31,.716,.09,.016);double sum=0;int count=0;float[] hsv=new float[3];
        for(int y=r.top;y<r.bottom;y++)for(int x=r.left;x<r.right;x++){
            Color.colorToHSV(b.getPixel(x,y),hsv);
            if(hsv[0]>25&&hsv[0]<70&&hsv[1]>.25&&hsv[2]>.65){sum+=x;count++;}
        }
        return count>0?Math.max(.93,Math.min(1.03,sum/count/b.getWidth()/.366)):1;
    }
    private static boolean gold(Bitmap b,Rect r,double x,double y){int lit=0,total=0;float[] hsv=new float[3];Rect a=box(b,r,x-.008,y-.004,.016,.008);for(int yy=a.top;yy<a.bottom;yy++)for(int xx=a.left;xx<a.right;xx++){Color.colorToHSV(b.getPixel(xx,yy),hsv);total++;if(hsv[0]>25&&hsv[0]<70&&hsv[1]>.25&&hsv[2]>.65)lit++;}return total>0&&lit>total*.45;}
    private static Rect box(Bitmap b,Rect a,double x,double y,double w,double h){return new Rect(Math.max(0,(int)(x*b.getWidth())),Math.max(0,Math.max(a.top,a.top+(int)(y*a.height()))),Math.min(b.getWidth(),(int)((x+w)*b.getWidth())),Math.min(b.getHeight(),Math.min(a.bottom,a.top+(int)((y+h)*a.height()))));}
    private static String crop(TextRecognizer reader,Bitmap image,Rect bounds,double x,double y,double w,double h)throws Exception{JSONArray rows=ocr(reader,image,bounds,x,y,w,h,4);StringBuilder text=new StringBuilder();for(int i=0;i<rows.length();i++)text.append(rows.getJSONObject(i).getString("text"));return TeamCaptureSession.clean(text.toString());}
    private static JSONArray ocr(TextRecognizer reader,Bitmap image,Rect bounds,double x,double y,double w,double h,int scale)throws Exception{
        Rect box=box(image,bounds,x,y,w,h);Bitmap part=Bitmap.createBitmap(image,box.left,box.top,box.width(),box.height());
        float factor=Math.min(scale,1400f/part.getWidth());Bitmap large=Bitmap.createScaledBitmap(part,Math.max(1,Math.round(part.getWidth()*factor)),Math.max(1,Math.round(part.getHeight()*factor)),true);
        try{Text text=Tasks.await(reader.process(InputImage.fromBitmap(large,0)),30,TimeUnit.SECONDS);JSONArray rows=new JSONArray();for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){Rect r=line.getBoundingBox();if(r!=null)rows.put(new JSONObject().put("text",line.getText()).put("x",box.left+r.left/(double)factor).put("y",box.top+r.top/(double)factor).put("width",r.width()/(double)factor).put("height",r.height()/(double)factor));}return rows;}
        // 无黑边时 createBitmap 可能返回源图本身，不能在整屏 OCR 后提前回收它。
        finally{if(large!=part)large.recycle();if(part!=image)part.recycle();}
    }
}
