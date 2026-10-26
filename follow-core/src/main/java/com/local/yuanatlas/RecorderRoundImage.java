package com.local.yuanatlas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 手抄图片按条纹或边框定位行列；OCR 只读取回合标签，操作与备注始终保留原图。 */
final class RecorderRoundImage {
    static final class Layout {
        final List<int[]> bands;final int left,label,note,right;final boolean externalGrid;
        Layout(List<int[]> bands,int left,int label,int note,int right){this(bands,left,label,note,right,false);}
        Layout(List<int[]> bands,int left,int label,int note,int right,boolean externalGrid){this.bands=bands;this.left=left;this.label=label;this.note=note;this.right=right;this.externalGrid=externalGrid;}
    }
    static final class Row {
        final String label;
        final Bitmap operations, note;
        Row(String label,Bitmap operations,Bitmap note){this.label=label;this.operations=operations;this.note=note;}
    }
    // 低清攻略图常将“回合”读成“回台”；仅容错后缀，回合数字仍须从原图识别且连续。
    private static final Pattern ROUND=Pattern.compile("([0-9]{1,3})\\s*回[合台]");
    private static final class LabelReadException extends IllegalArgumentException {
        final int row;
        LabelReadException(int row,String message){super(message);this.row=row;}
    }
    private static int rgbDistance(int a,int b){return Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));}
    private static boolean stripe(Bitmap image,int y){
        int w=image.getWidth(),votes=0;
        // 靠近左边框取样，避开居中的“1回合”等文字。
        for(float ratio:new float[]{.005f,.009f,.013f}){
            int color=image.getPixel(Math.max(2,Math.round(w*ratio)),y),r=color>>16&255,g=color>>8&255,b=color&255;
            // 各主题都使用白色普通行和浅色条纹；按离白色的距离判断，不限定青绿色。
            if(r+g+b<750)votes++;
        }
        return votes>=2;
    }
    /** 网格底色在每一行翻转；过滤 JPEG 边缘的 1～3 像素噪声。 */
    static List<int[]> rowBands(Bitmap image){
        return rowBands(image,image.getHeight());
    }
    private static List<int[]> rowBands(Bitmap image,int h){
        boolean[] mask=new boolean[h];
        for(int y=0;y<h;y++)mask[y]=stripe(image,y);
        boolean[] smooth=new boolean[h];
        for(int y=0;y<h;y++){int votes=0;for(int d=-3;d<=3;d++)if(mask[Math.max(0,Math.min(h-1,y+d))])votes++;smooth[y]=votes>=4;}
        List<int[]> runs=new ArrayList<>();int start=0;
        for(int y=1;y<=h;y++)if(y==h||smooth[y]!=smooth[start]){
            if(y-start>=Math.max(15,h/220))runs.add(new int[]{start,y,smooth[start]?1:0});
            start=y;
        }
        // 标题为白色，密探表头为有色，首个白色数据行是第 1 回合。
        int header=-1;
        for(int i=0;i+1<runs.size();i++)if(runs.get(i)[2]==1&&runs.get(i+1)[2]==0){header=i;break;}
        if(header<0||runs.size()-header-1<1)throw new IllegalArgumentException("未找到完整的记录仪表头和回合行");
        List<int[]> rows=new ArrayList<>();
        for(int i=header+1;i<runs.size();i++){
            int[] run=runs.get(i);if(run[1]-run[0]>=Math.max(22,h/170))rows.add(new int[]{run[0],run[1]});
        }
        if(rows.isEmpty()||rows.size()>200)throw new IllegalArgumentException("回合行数量异常，请选择完整操作表图片");
        return rows;
    }
    /** 跟打码是表格外的附加文字，不进入抄表裁图，也不在此处解密。 */
    static int tableHeight(Bitmap image,Text ocr){
        for(Text.TextBlock block:ocr.getTextBlocks())for(Text.Line line:block.getLines()){
            String text=line.getText().replaceAll("\\s+","");Rect box=line.getBoundingBox();
            if(box==null||!text.contains("允许自动跟打")||!text.contains("专用加密码"))continue;
            int font=Math.max(1,box.height()),from=Math.max(1,box.top-font*3),to=Math.min(image.getHeight()-4,box.top);
            // 新图说明在纹理下方：先定位整幅深色纹理的上沿，再使用旧说明位置的回退规则。
            int textureTop=textureTopAboveDescription(image,to,from);
            if(textureTop>=0)return textureTop;
            // 从标题向上查找表格底边，避免白色末行与白色附加区合成一个超高回合。
            for(int y=to;y>=from;y--){
                int votes=0;
                for(int part=1;part<=48;part++){
                    int x=image.getWidth()*part/49;
                    if(rgbDistance(image.getPixel(x,y),image.getPixel(x,y+3))>60)votes++;
                }
                if(votes>=36)return y+1;
            }
            // 极浅主题或压缩导致边线不可见时，最多保留少量留白，不能把标题与密文当作回合。
            return Math.max(1,Math.min(image.getHeight(),box.top-font));
        }
        // 未附跟打码时也要剔除导出白边，避免把留白当成空回合或并入最后一个白底回合。
        int width=image.getWidth(),height=image.getHeight();int[] pixels=new int[width];
        for(int y=height-1;y>=0;y--){
            image.getPixels(pixels,0,width,0,y,width,1);int ink=0;
            for(int color:pixels){
                if((color>>>24)>127&&Math.min(color>>16&255,Math.min(color>>8&255,color&255))<235)ink++;
            }
            // 保留表格底边；零星 JPEG 噪点不作为边界，小于几像素的原有圆角仍保持旧尺寸。
            if(ink>=Math.max(2,width/200))return height-y-1>=Math.max(4,width/200)?y+1:height;
        }
        return height;
    }
    /** 仅用于排除 v2 纹理区域，不解码或推断跟打内容；均匀分段平均过滤普通文字笔画。 */
    static int textureTopAboveDescription(Bitmap image,int bottom,int searchTop){
        int width=image.getWidth(),run=0;int[] pixels=new int[width];
        for(int y=Math.min(bottom,image.getHeight()-1);y>=0;y--){
            image.getPixels(pixels,0,width,0,y,width,1);int dark=0;
            for(int part=0;part<32;part++){
                int left=part*width/32,right=(part+1)*width/32;long light=0;
                for(int x=left;x<right;x++){int color=pixels[x];light+=(color>>16&255)+(color>>8&255)+(color&255);}
                if(right>left&&light<185L*3*(right-left))dark++;
            }
            if(dark>=24)run++;
            else if(run>0)return run>=Math.max(4,width*24/480)?y+1:-1;
            if(run==0&&y<searchTop)return -1;
        }
        return -1;
    }
    private static int lineScore(Bitmap image,int x,List<int[]> rows){
        int total=0,w=image.getWidth();
        if(x<4||x>w-5)return 0;
        int stride=Math.max(1,rows.size()/12);
        for(int rowIndex=0;rowIndex<rows.size();rowIndex+=stride)for(int part=1;part<=4;part++){
            int[] row=rows.get(rowIndex);
            int y=row[0]+(row[1]-row[0])*part/5;
            int center=image.getPixel(x,y),left=image.getPixel(x-3,y),right=image.getPixel(x+3,y);
            total+=Math.min(rgbDistance(center,left),rgbDistance(center,right));
        }
        return total;
    }
    private static int divider(int[] scores,int from,int to){
        int winner=from,score=0;
        for(int x=from;x<=to;x++){int current=scores[x];if(current>score){score=current;winner=x;}}
        return winner;
    }
    private static int[] grid(Bitmap image,List<int[]> rows){
        int w=image.getWidth(),best=0,label=0,note=w;
        int[] scores=new int[w];for(int x=4;x<w-4;x++)scores[x]=lineScore(image,x,rows);
        // 有备注时是“标签 + 5 操作 + 3 格宽备注”；没有备注时是“标签 + 5 操作”。
        for(int slots:new int[]{8,5})for(int candidate=Math.round(w*.07f);candidate<=Math.round(w*.30f);candidate++){
            float step=(w-candidate)/(float)slots;int sum=0,firstEdge=0,lastEdge=w;
            for(int column=0;column<=(slots==8?5:4);column++){
                int predicted=Math.round(candidate+column*step);
                int edge=divider(scores,Math.max(4,predicted-3),Math.min(w-5,predicted+3));
                if(column==0)firstEdge=edge;
                if(column==5)lastEdge=edge;
                sum+=scores[edge];
            }
            int count=slots==8?6:5,average=sum/count;
            if(average>best){best=average;label=firstEdge;note=lastEdge;}
        }
        if(label==0||scores[label]<Math.min(rows.size(),12)*25)throw new IllegalArgumentException("未识别到记录仪的操作列边界");
        return new int[]{label,note};
    }
    private static boolean noteInk(Bitmap image,int left,int right,int top,int bottom){
        int dark=0;
        for(int y=top+8;y<bottom-8;y+=3)for(int x=left+8;x<right-8;x+=3){
            int color=image.getPixel(x,y),r=color>>16&255,g=color>>8&255,b=color&255;
            if(r<120&&g<125&&b<125&&++dark>=8)return true;
        }
        return false;
    }
    private static List<String> labels(Text ocr,List<int[]> bands,int left,int labelEdge){
        List<String> result=new ArrayList<>();
        for(int[] band:bands){
            int top=band[0],bottom=band[1];StringBuilder label=new StringBuilder();
            for(Text.TextBlock block:ocr.getTextBlocks())for(Text.Line line:block.getLines()){
                Rect box=line.getBoundingBox();if(box==null||box.centerY()<top||box.centerY()>=bottom)continue;
                if(box.left>=left&&box.right<=labelEdge)label.append(line.getText());
                else for(Text.Element element:line.getElements()){
                    // 部分设备把回合和操作合并成一行，按词块边界保留左侧标签。
                    Rect word=element.getBoundingBox();if(word!=null&&word.left>=left&&word.right<=labelEdge&&word.centerY()>=top&&word.centerY()<bottom)label.append(element.getText());
                }
            }
            result.add(label.toString());
        }
        return result;
    }
    private static List<String> checkedLabels(List<String> labels,boolean numberedOnly){
        List<String> names=new ArrayList<>();int previous=0;
        for(int i=0;i<labels.size();i++){
            String raw=labels.get(i).replaceAll("\\s+","").replaceAll("^[|丨]+|[|丨]+$","");
            Matcher match=ROUND.matcher(raw);String name;
            if(match.find()){
                int number=Integer.parseInt(match.group(1));
                if(number!=previous+1)throw new LabelReadException(i,"第"+(i+1)+"行回合数不连续，请选择完整操作表图片");
                previous=number;name=number+"回合";
            // 外部网格表的首列是连续回合；“6合”等漏字应重读，不能被当成上一回合的自定义续行。
            }else if(!numberedOnly&&previous>0&&!raw.isEmpty()&&!raw.matches("[0-9]+[回同].*"))name=previous+"回合·"+raw;
            else throw new LabelReadException(i,"第"+(i+1)+"行回合标签无法识别，请选择更清晰的完整图片");
            names.add(name);
        }
        if(previous==0)throw new LabelReadException(0,"没有识别到回合数");
        return names;
    }
    private static List<Row> cropRows(Bitmap image,Layout layout,List<String> labels){
        // 先校验全部标签再分配裁图，重试 OCR 时不保留一批无效的大图。
        List<String> names=checkedLabels(labels,layout.externalGrid);List<Row> result=new ArrayList<>();List<int[]> bands=layout.bands;
        int labelEdge=layout.label,noteEdge=layout.note;
        try{for(int i=0;i<bands.size();i++){
            int[] band=bands.get(i);int top=band[0],bottom=band[1];
            // 外部攻略常在格子底部叠加修正文字，边框上下多保留少量像素，避免裁掉标注笔画。
            int y=layout.externalGrid?Math.max(0,top-1):Math.min(bottom-1,top+2);
            int height=layout.externalGrid?Math.min(image.getHeight()-y,bottom-y+3):Math.max(1,bottom-y-1);
            // 裁切结果复制为独立像素，识别完成后可以释放原始大图。
            Bitmap operations=Bitmap.createBitmap(image,labelEdge+1,y,Math.max(1,noteEdge-labelEdge-2),height).copy(Bitmap.Config.ARGB_8888,false);
            // 外部表格整列保留原底色，浅色备注、加字和水印也不依赖文字深浅判断。
            Bitmap note=noteEdge<layout.right&&(layout.externalGrid||noteInk(image,noteEdge,layout.right,top,bottom))?Bitmap.createBitmap(image,noteEdge+1,y,Math.max(1,layout.right-noteEdge-2),height).copy(Bitmap.Config.ARGB_8888,false):null;
            result.add(new Row(names.get(i),operations,note));
        }}catch(RuntimeException|OutOfMemoryError error){for(Row row:result){row.operations.recycle();if(row.note!=null)row.note.recycle();}throw error;}
        return result;
    }
    static List<Row> read(Bitmap image,Text ocr){
        Layout layout=layout(image,ocr);return cropRows(image,layout,labels(ocr,layout.bands,layout.left,layout.label));
    }
    private static Layout layout(Bitmap image,Text ocr){
        int height=tableHeight(image,ocr);
        // 原导出图优先沿用条纹算法；留白边距、非白底等普通攻略表再按实际网格定位。
        try{List<int[]> bands=rowBands(image,height);int[] columns=grid(image,bands);return new Layout(bands,0,columns[0],columns[1],image.getWidth());}
        catch(IllegalArgumentException error){Layout manual=RecorderManualGrid.find(image,height);if(manual!=null)return manual;throw new IllegalArgumentException("未找到完整表格，请保留回合列、五列操作和清晰边框");}
    }
    /** 整图漏字或回合不连续时重读独立标签格，不根据行号推算回合。 */
    static Task<List<Row>> readWithRetry(Bitmap image,Text ocr,TextRecognizer client){
        try{
            Layout layout=layout(image,ocr);
            return focusedLabels(image,layout,client,labels(ocr,layout.bands,layout.left,layout.label),new int[layout.bands.size()]);
        }catch(Exception error){return Tasks.forException(error);}
    }
    private static Task<List<Row>> focusedLabels(Bitmap image,Layout layout,TextRecognizer client,List<String> labels,int[] attempts){
        int row;
        try{return Tasks.forResult(cropRows(image,layout,labels));}
        catch(LabelReadException error){
            row=error.row;if(attempts[row]>=2)return Tasks.forException(error);
        }
        // 只重读出错的格子，保留已经正确的标签。先放大原色，仍失败再去除浅色背景。
        Bitmap cell=labelCell(image,layout.bands.get(row),layout.left,layout.label,++attempts[row]==2);
        // 顺序处理并及时释放，每次只保留一张放大后的标签，长表也不会并发占用内存。
        return client.process(InputImage.fromBitmap(cell,0)).continueWith(task->{
            try{return task.getResult().getText();}finally{cell.recycle();}
        }).onSuccessTask(text->{labels.set(row,text);return focusedLabels(image,layout,client,labels,attempts);});
    }
    private static Bitmap labelCell(Bitmap image,int[] band,int left,int edge,boolean contrast){
        int width=Math.max(1,edge-left-6),height=Math.max(1,Math.min(band[1]-band[0]-6,Math.round(width*1.2f)));
        int top=band[0]+(band[1]-band[0]-height)/2;
        Bitmap ink=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        // 标签在格子中央；去掉边框和浅色背景，避免网格被识别成竖线、浅色字被漏掉。
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int c=image.getPixel(left+x+3,top+y),luma=((c>>16&255)*299+(c>>8&255)*587+(c&255)*114)/1000;
            ink.setPixel(x,y,contrast?(luma<190?Color.BLACK:Color.WHITE):c);
        }
        float scale=Math.max(2f,Math.min(4f,384f/width));int w=Math.round(width*scale),h=Math.round(height*scale),padding=24;
        Bitmap enlarged=Bitmap.createBitmap(w+2*padding,h+2*padding,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(enlarged);canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(ink,null,new Rect(padding,padding,padding+w,padding+h),new Paint(Paint.FILTER_BITMAP_FLAG));ink.recycle();
        return enlarged;
    }
}
