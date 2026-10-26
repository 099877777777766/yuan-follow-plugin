package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

/** 自动采集专用 OCR：保留整行与颜色，避免星石识别的分列规则打散技能正文。 */
final class RuYuanVision {
    static Rect content(Bitmap image) {
        return GameViewport.detect(image);
    }
    static JSONObject read(Bitmap original,boolean identity)throws Exception{return read(original,identity,false);}
    static JSONObject read(Bitmap original,boolean identity,boolean codeYuan)throws Exception{return read(original,identity,codeYuan,true);}
    static JSONObject read(Bitmap original,boolean identity,boolean codeYuan,boolean readCells)throws Exception{
        return read(original,identity,codeYuan,readCells,null);
    }
    static JSONObject read(Bitmap original,boolean identity,boolean codeYuan,boolean readCells,Rect overlay)throws Exception{
        // 局部识别在游戏宽度内运行，完成后还原为原始截图坐标。
        try(GameViewport viewport=GameViewport.horizontal(original)){
            if(viewport.image!=original)return viewport.restore(read(viewport.image,identity,codeYuan,readCells,viewport.local(overlay)));
        }
        // 所有分区裁剪也使用同一份去浮窗副本，防止全屏过滤后技能/命盘再次混入控制文案。
        // 留边必须先在原图计算，不能把副本中的黑色排除区域当成游戏新增留边。
        Rect bounds=content(original);Bitmap analysis=CaptureOcrImage.excluding(original,overlay);
        try{return analyze(analysis,identity,codeYuan,readCells,bounds).put("ocrOverlay",overlay==null?"none":overlay.toShortString());}
        finally{if(analysis!=original)analysis.recycle();}
    }
    /** 页面文字与像素边线分开验证；与状态机使用同一份 OCR 行。 */
    static void locateFateGrid(Bitmap image,Rect bounds,JSONObject page)throws Exception{
        boolean button=false;JSONArray lines=page.getJSONArray("lines");
        // 状态机先 clean 再认按钮；这里也必须去空白，避免“开始 改命”认出页面却跳过边线检测。
        for(int n=0;n<lines.length();n++)if(RuYuanAutoCapture.clean(lines.getJSONObject(n).optString("text")).matches("(?s).*[开開]始[改政]命.*")){button=true;break;}
        page.put("fateGridStatus",button?"edges_missing":"button_missing");
        if(!button)return;
        Rect grid=RuYuanFateGrid.locate(image,bounds);
        if(grid!=null)page.put("fateGrid",new JSONObject().put("left",grid.left).put("top",grid.top).put("right",grid.right).put("bottom",grid.bottom)).put("fateGridStatus","located");
    }
    private static JSONObject analyze(Bitmap original,boolean identity,boolean codeYuan,boolean readCells,Rect bounds)throws Exception{
        float scale=Math.max(1,Math.min(2.5f,1400f/original.getWidth()));
        Bitmap bitmap=Bitmap.createScaledBitmap(original,Math.round(original.getWidth()*scale),Math.round(original.getHeight()*scale),true);
        try(TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
            Text text=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),60,TimeUnit.SECONDS);JSONArray lines=new JSONArray();
            for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
                Rect r=line.getBoundingBox();if(r==null)continue;
                lines.put(new JSONObject().put("text",CapturePercent.read(recognizer,bitmap,r,line.getText())).put("x",r.left/scale).put("y",r.top/scale).put("width",r.width()/scale).put("height",r.height()/scale).put("ink",ink(bitmap,r)));
            }
            JSONObject page=new JSONObject().put("width",original.getWidth()).put("height",original.getHeight()).put("top",bounds.top).put("contentHeight",bounds.height()).put("lines",lines).put("talentPixels",talentPixels(original,bounds));
            // 短屏的技能栏会整体上移；使用本帧标签定位其上方图标，避免套用长屏高度。
            JSONArray targets=new JSONArray();
            String[] skillLabels={"普攻","技能","队长技能","天赋"};
            // 先确认两端入口，再用本帧栏宽裁中间两列，兼容短屏技能栏横向收紧。
            for(int skillIndex:new int[]{0,3,1,2}){
                String label=skillLabels[skillIndex];
                JSONObject target=null;
                for(int n=0;n<lines.length();n++){
                    JSONObject row=lines.getJSONObject(n);double x=(row.getDouble("x")+row.getDouble("width")/2)/original.getWidth(),y=(row.getDouble("y")-bounds.top)/bounds.height();
                    if(TeamCaptureSession.clean(row.getString("text")).replaceAll("[^\\p{IsHan}]","").equals(label)&&x>.25&&y>.55&&y<.84)
                        target=new JSONObject().put("x",x).put("y",(row.getDouble("y")-row.getDouble("height")*1.5-bounds.top)/bounds.height());
                }
                // OCR 可能把“技能 队长技能 天赋”合并为一行，按各列小框重读完整标签。
                if(target==null){
                    double expected=(codeYuan?new double[]{.451,.601,.751,.9}:new double[]{.537,.674,.807,.941})[skillIndex];
                    JSONObject first=targets.optJSONObject(0),last=targets.optJSONObject(3);
                    if((skillIndex==1||skillIndex==2)&&first!=null&&last!=null){
                        double span=last.getDouble("x")-first.getDouble("x");
                        if(span>.24&&span<.60&&Math.abs(first.getDouble("y")-last.getDouble("y"))<.05)
                            expected=first.getDouble("x")+span*skillIndex/3;
                    }
                    for(int n=0;n<lines.length();n++){
                        JSONObject row=lines.getJSONObject(n);double y=(row.getDouble("y")-bounds.top)/bounds.height();
                        if(y<.55||y>.84||!TeamCaptureSession.clean(row.getString("text")).contains(label))continue;
                        Rect box=rect(original,bounds,Math.max(0,expected-.08),Math.max(0,y-.004),Math.min(.16,1-Math.max(0,expected-.08)),row.getDouble("height")/bounds.height()+.008);
                        Bitmap part=Bitmap.createBitmap(original,box.left,box.top,box.width(),box.height()),large=Bitmap.createScaledBitmap(part,part.getWidth()*3,part.getHeight()*3,true);
                        try{
                            Text focused=Tasks.await(recognizer.process(InputImage.fromBitmap(large,0)),30,TimeUnit.SECONDS);
                            for(Text.TextBlock block:focused.getTextBlocks())for(Text.Line line:block.getLines()){
                                Rect r=line.getBoundingBox();if(r!=null&&TeamCaptureSession.clean(line.getText()).replaceAll("[^\\p{IsHan}]","").equals(label))
                                    target=new JSONObject().put("x",(box.left+r.exactCenterX()/3)/original.getWidth()).put("y",(box.top+(r.top-r.height()*1.5)/3-bounds.top)/bounds.height());
                            }
                        }finally{large.recycle();part.recycle();}
                        if(target!=null)break;
                    }
                }
                // 等分只用来确定补读范围；点击坐标必须来自补读到的完整标签。
                targets.put(skillIndex,target==null?JSONObject.NULL:target);
            }
            page.put("skillTargets",targets);
            locateFateGrid(original,bounds,page);
            Rect popup=RuYuanPopup.locate(original,bounds,codeYuan);
            if(popup!=null){
                // 在 OCR 前裁掉框外内容，避免同一文字行跨越边框后再过滤不干净。
                Bitmap part=Bitmap.createBitmap(original,popup.left,popup.top,popup.width(),popup.height());Bitmap enlarged=Bitmap.createScaledBitmap(part,Math.round(part.getWidth()*scale),Math.round(part.getHeight()*scale),true);
                try{
                    Text inside=Tasks.await(recognizer.process(InputImage.fromBitmap(enlarged,0)),60,TimeUnit.SECONDS);JSONArray rows=new JSONArray();
                    for(Text.TextBlock block:inside.getTextBlocks())for(Text.Line line:block.getLines()){Rect r=line.getBoundingBox();if(r!=null)rows.put(new JSONObject().put("text",CapturePercent.read(recognizer,enlarged,r,line.getText())).put("x",popup.left+r.left/scale).put("y",popup.top+r.top/scale).put("width",r.width()/scale).put("height",r.height()/scale));}
                    page.put("popup",new JSONObject().put("left",popup.left).put("top",popup.top).put("right",popup.right).put("bottom",popup.bottom)).put("popupLines",rows);
                }finally{if(enlarged!=part)enlarged.recycle();part.recycle();}
            }
            if(codeYuan&&readCells&&(text.getText().contains("一键")&&text.getText().contains("逆天")||text.getText().contains("再次改命")&&text.getText().contains("接受"))){
                boolean dual=text.getText().contains("再次改命");JSONArray cells=new JSONArray();
                // 宫格逐格裁剪再识别，防止 OCR 把相邻格的第一行合并成一条。
                for(int i=0;i<(dual?24:12);i++){int[] cell=RuYuanAutoCapture.CELLS[i%12];double x=.138+cell[0]*.24,y=dual?(i<12?.185:.548)+cell[1]*.086:.270+cell[1]*.113,half=dual?.040:.052;
                    Rect box=rect(original,bounds,x-.115,y-half,.230,half*2);Bitmap part=Bitmap.createBitmap(original,box.left,box.top,box.width(),box.height());Bitmap large=Bitmap.createScaledBitmap(part,part.getWidth()*4,part.getHeight()*4,true);
                    try{Text value=Tasks.await(recognizer.process(InputImage.fromBitmap(large,0)),30,TimeUnit.SECONDS);JSONArray rows=new JSONArray();for(Text.TextBlock block:value.getTextBlocks())for(Text.Line line:block.getLines()){Rect r=line.getBoundingBox();if(r!=null)rows.put(new JSONObject().put("text",line.getText()).put("x",box.left+r.left/4f).put("y",box.top+r.top/4f).put("width",r.width()/4f).put("height",r.height()/4f).put("ink",ink(large,r)));}cells.put(rows);}finally{large.recycle();part.recycle();}
                }
                page.put("codeCells",cells);
            }
            if(codeYuan&&text.getText().matches("(?s).*一键.*[装备卸下].*")&&text.getText().contains("逆天")){
                // 代号鸢普通命盘页的说明在下方面板，只裁这个区域，排除宫格和底部按钮。
                Rect detail=rect(original,bounds,.205,.687,.76,.185);Bitmap part=Bitmap.createBitmap(original,detail.left,detail.top,detail.width(),detail.height());Bitmap enlarged=Bitmap.createScaledBitmap(part,part.getWidth()*3,part.getHeight()*3,true);
                try{Text inside=Tasks.await(recognizer.process(InputImage.fromBitmap(enlarged,0)),60,TimeUnit.SECONDS);JSONArray detailRows=new JSONArray();for(Text.TextBlock block:inside.getTextBlocks())for(Text.Line line:block.getLines()){Rect r=line.getBoundingBox();String value=r==null?line.getText():CapturePercent.read(recognizer,enlarged,r,line.getText());if(r!=null&&!value.matches(".*[主辅]星.*|.*当前.*[宫容]格.*|.*加成不生效.*|解锁|[+十]|[0-9]+"))detailRows.put(new JSONObject().put("text",value).put("x",detail.left+r.left/3f).put("y",detail.top+r.top/3f).put("width",r.width()/3f).put("height",r.height()/3f));}page.put("detailLines",detailRows);}finally{enlarged.recycle();part.recycle();}
            }
            if(identity){
                // 竖排小字按视频对应资料栏重排为横排，再识别；读不到就保留空值供最终补充。
                page.put("attributeText",attribute(recognizer,original,bounds,codeYuan));
                page.put("roleText",column(recognizer,original,bounds,codeYuan?.05:.043,codeYuan?.35:.364,codeYuan?.041:.065,.044,2));
                page.put("rarityText",crop(recognizer,original,bounds,.18,.18,.12,.06));
                // 短屏身份栏位置变化时，只用左侧独立字段文字补读，不从人物或标签颜色猜属性。
                if(original.getWidth()/(double)bounds.height()>.53){
                    String foundAttribute="",foundRole="";boolean conflict=false;
                    for(int n=0;n<lines.length();n++){
                        JSONObject row=lines.getJSONObject(n);double x=(row.getDouble("x")+row.getDouble("width")/2)/original.getWidth(),y=(row.getDouble("y")-bounds.top)/bounds.height();
                        if(x>.13||y<.22||y>.53)continue;
                        String value=TeamCaptureSession.clean(row.getString("text")).replaceAll("[^\\p{IsHan}]",""),candidate=RuYuanAutoCapture.attributeValue(value);
                        if(!candidate.isEmpty()){
                            // 在原图同一文字框内再次 OCR，两个结果一致才填入。
                            String focused=crop(recognizer,original,bounds,Math.max(0,(row.getDouble("x")-3)/original.getWidth()),Math.max(0,(row.getDouble("y")-bounds.top-3)/bounds.height()),(row.getDouble("width")+6)/original.getWidth(),(row.getDouble("height")+6)/bounds.height());
                            if(candidate.equals(RuYuanAutoCapture.attributeValue(focused.replaceAll("[^\\p{IsHan}]","")))){
                                if(!foundAttribute.isEmpty()&&!foundAttribute.equals(candidate))conflict=true;foundAttribute=candidate;
                                // 职业是属性字下方的独立竖列，小字整页漏读时按已确认的属性字位置补读。
                                {
                                    // 图标与属性字可能合并成高行，以下沿和字宽还原单字高度。
                                    double letterHeight=Math.min(row.getDouble("height"),row.getDouble("width")*1.1),letterTop=row.getDouble("y")+row.getDouble("height")-letterHeight;
                                    String below=TeamCaptureSession.clean(crop(recognizer,original,bounds,Math.max(0,(row.getDouble("x")-row.getDouble("width")*.2)/original.getWidth()),(letterTop+letterHeight*1.1-bounds.top)/bounds.height(),row.getDouble("width")*1.5/original.getWidth(),letterHeight*5/bounds.height()));
                                    below=RuYuanAutoCapture.clean(below);
                                    for(String role:new String[]{"破军","龙盾","岐黄","神纪","诡道"})if(below.contains(role))foundRole=role;
                                    // 竖排双字必须完整落入各自格子；容纳 OCR 框几像素误差，仍只接受完整职业名。
                                    for(double offset:new double[]{2.5,2.7}){
                                        if(!foundRole.isEmpty())break;
                                        below=RuYuanAutoCapture.clean(column(recognizer,original,bounds,Math.max(0,(row.getDouble("x")-row.getDouble("width")*.2)/original.getWidth()),(letterTop+letterHeight*offset-bounds.top)/bounds.height(),row.getDouble("width")*1.6/original.getWidth(),letterHeight*2.4/bounds.height(),2));
                                        for(String role:new String[]{"破军","龙盾","岐黄","神纪","诡道"})if(below.contains(role))foundRole=role;
                                    }
                                }
                            }
                        }
                        if(value.matches("破军|龙盾|岐黄|神纪|诡道"))foundRole=value;
                    }
                    if(!conflict&&!foundAttribute.isEmpty())page.put("attributeText",foundAttribute);
                    if(!foundRole.isEmpty())page.put("roleText",foundRole);
                }
            }
            return page;
        }finally{if(bitmap!=original)bitmap.recycle();}
    }
    /** 比较弹窗正文图像是否移动，不让 OCR 错字抖动引发反复到底滑动。 */
    private static String talentPixels(Bitmap image,Rect bounds){
        Rect r=rect(image,bounds,.36,.12,.56,.53);Bitmap part=Bitmap.createBitmap(image,r.left,r.top,r.width(),r.height());Bitmap small=Bitmap.createScaledBitmap(part,64,80,true);
        byte[] values=new byte[64*80];for(int y=0;y<80;y++)for(int x=0;x<64;x++){int c=small.getPixel(x,y);values[y*64+x]=(byte)((Color.red(c)*3+Color.green(c)*6+Color.blue(c))/10);}
        if(small!=part)small.recycle();part.recycle();return android.util.Base64.encodeToString(values,android.util.Base64.NO_WRAP);
    }
    private static String crop(TextRecognizer r,Bitmap b,Rect area,double x,double y,double w,double h)throws Exception{
        Rect rect=rect(b,area,x,y,w,h);Bitmap part=Bitmap.createBitmap(b,rect.left,rect.top,rect.width(),rect.height());Bitmap large=Bitmap.createScaledBitmap(part,part.getWidth()*4,part.getHeight()*4,true);
        try{return Tasks.await(r.process(InputImage.fromBitmap(large,0)),30,TimeUnit.SECONDS).getText();}finally{if(large!=part)large.recycle();part.recycle();}
    }
    /** 单独裁属性字，不含上方图标、右侧定位标签及下方职业。 */
    static String attribute(TextRecognizer r,Bitmap b,Rect bounds,boolean codeYuan)throws Exception{
        String icon=CaptureAttribute.icon(b,bounds,codeYuan);
        String text=RuYuanAutoCapture.attributeValue(column(r,b,bounds,.052,codeYuan?.316:.327,.040,.023,1));
        // 图标可区分易混的阴/阳；专栏文字与图标冲突时不猜测，留给用户核对。
        return !icon.isEmpty()?(text.isEmpty()||text.equals(icon)?icon:""):text;
    }
    private static String column(TextRecognizer r,Bitmap b,Rect area,double x,double y,double w,double h,int count)throws Exception{
        Rect rect=rect(b,area,x,y,w,h);Bitmap strip=Bitmap.createBitmap(count*180,180,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(strip);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        for(int i=0;i<count;i++){Rect source=new Rect(rect.left,rect.top+rect.height()*i/count,rect.right,rect.top+rect.height()*(i+1)/count);canvas.drawBitmap(b,source,new Rect(i*180+10,10,(i+1)*180-10,170),paint);}
        try{return Tasks.await(r.process(InputImage.fromBitmap(strip,0)),30,TimeUnit.SECONDS).getText();}finally{strip.recycle();}
    }
    private static Rect rect(Bitmap b,Rect area,double x,double y,double w,double h){int left=(int)(x*b.getWidth()),top=area.top+(int)(y*area.height());return new Rect(left,top,Math.min(b.getWidth(),left+(int)(w*b.getWidth())),Math.min(b.getHeight(),top+(int)(h*area.height())));}
    static String ink(Bitmap b,Rect rect){
        int gold=0,purple=0,blue=0;float[] hsv=new float[3];
        for(int y=Math.max(0,rect.top);y<Math.min(b.getHeight(),rect.bottom);y+=2)for(int x=Math.max(0,rect.left);x<Math.min(b.getWidth(),rect.right);x+=2){
            Color.colorToHSV(b.getPixel(x,y),hsv);if(hsv[1]<.16||hsv[2]<.52)continue;
            if(hsv[0]>=25&&hsv[0]<=75)gold++;else if(hsv[0]>=245&&hsv[0]<=330)purple++;else if(hsv[0]>=165&&hsv[0]<=230)blue++;
        }
        int max=Math.max(gold,Math.max(purple,blue));if(max<8)return "";return max==gold?"gold":max==purple?"purple":"blue";
    }
}
