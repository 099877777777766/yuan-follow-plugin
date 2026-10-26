package com.local.yuanatlas;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** 星色/龙气整行多选与我方阵亡单元格多选共用整表；长表复用行视图。 */
final class RecorderConditionRoundPicker {
    private final Context context;private final RecorderFollowTable table;private final String title;
    private final Set<Integer> selected=new TreeSet<>();private RecorderOverlayTheme theme;
    private final Map<Integer,Integer> cells=new TreeMap<>();private final boolean cellMode;
    private boolean singleRound;private Set<Integer> availableRounds;
    private final Dialog dialog;private final LinearLayout body,header;private final RecorderWatermarkedLayout tableContent;private final TextView heading,hint,status,clear;
    private final Button cancel,confirm;private final ListView list;private final Rows adapter=new Rows();
    RecorderConditionRoundPicker(Context context,RecorderFollowTable table,String title,Set<Integer> chosen,RecorderOverlayTheme theme,int windowType,Consumer<Set<Integer>> accepted){
        this(context,table,title,theme,windowType,false);selected.addAll(chosen);selected.retainAll(RecorderRoundConditions.rounds(table));
        confirm.setOnClickListener(v->{accepted.accept(new TreeSet<>(selected));dismiss();});refresh();
    }
    /** 条件动作块一次绑定一个回合；其余检测入口继续使用原来的多选模式。 */
    RecorderConditionRoundPicker(Context context,RecorderFollowTable table,String title,int chosen,Set<Integer> available,RecorderOverlayTheme theme,int windowType,Consumer<Set<Integer>> accepted){
        this(context,table,title,chosen>0?Collections.singleton(chosen):Collections.emptySet(),theme,windowType,accepted);
        singleRound=true;availableRounds=new TreeSet<>(available);
        hint.setText("点击选择一个回合，续行一起选中。已被其他块使用或没有诸葛亮行动的回合不可选。");refresh();
    }
    /** 同一选表入口的单元格模式；续行同列映射到所属回合，不创建重复检测。 */
    RecorderConditionRoundPicker(Context context,RecorderFollowTable table,String title,Map<Integer,Integer> chosen,RecorderOverlayTheme theme,int windowType,Consumer<Map<Integer,Integer>> accepted){
        this(context,table,title,theme,windowType,true);cells.putAll(chosen);cells.keySet().retainAll(RecorderRoundConditions.rounds(table));
        confirm.setOnClickListener(v->{accepted.accept(new TreeMap<>(cells));dismiss();});refresh();
    }
    private RecorderConditionRoundPicker(Context context,RecorderFollowTable table,String title,RecorderOverlayTheme theme,int windowType,boolean cellMode){
        this.context=context;this.table=table;this.title=title;this.theme=theme;this.cellMode=cellMode;
        dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.getWindow().setType(windowType);
        body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(16),dp(12),dp(12));
        heading=text(title+(cellMode?" · 选择单元格":" · 选择回合"),18);heading.setTypeface(null,Typeface.BOLD);body.addView(heading);
        hint=text(cellMode?"点击密探单元格可多选，空白操作格也可选。同回合续行同列一起选中，在该回合第一步操作前检查一次。":"点击整行可多选，同一回合的续行会一起选中。"+(title.equals("紫星检测")||title.equals("橙星检测")?"同回合改选星色将替换原要求。":title.startsWith("龙脉重开")?"同回合改选龙脉内外将替换原要求；状态不符时重开。":""),11);hint.setPadding(0,dp(8),0,dp(12));body.addView(hint);
        // 所有选择入口共用同一水印层，覆盖不透明选中格；列表滚动不移走作者标记。
        tableContent=new RecorderWatermarkedLayout(context,table.roleName,theme.muted);tableContent.setOrientation(LinearLayout.VERTICAL);body.addView(tableContent,new LinearLayout.LayoutParams(-1,0,1));
        if(table.headerNote!=null&&!table.headerNote.isEmpty()){TextView note=text(table.headerNote,12);note.setTag("table-note");note.setPadding(0,0,0,dp(8));tableContent.addView(note);}
        header=row();bind(header,-1);tableContent.addView(header,new LinearLayout.LayoutParams(-1,-2));
        list=new ListView(context);list.setContentDescription("检测回合整表");list.setDivider(null);list.setCacheColorHint(Color.TRANSPARENT);list.setAdapter(adapter);tableContent.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout selection=new LinearLayout(context);selection.setGravity(Gravity.CENTER_VERTICAL);selection.setPadding(0,dp(8),0,dp(8));
        status=text("",12);status.setContentDescription("已选检测回合数量");selection.addView(status,new LinearLayout.LayoutParams(0,dp(32),1));clear=text("清空",13);clear.setGravity(Gravity.CENTER);clear.setContentDescription("清空检测回合");selection.addView(clear,new LinearLayout.LayoutParams(dp(52),dp(40)));clear.setOnClickListener(v->{selected.clear();cells.clear();refresh();});body.addView(selection);
        LinearLayout buttons=new LinearLayout(context);cancel=new Button(context);cancel.setText("取消");cancel.setContentDescription("取消选择检测回合");confirm=new Button(context);confirm.setText("确定");confirm.setContentDescription("确定选择检测回合");
        buttons.addView(cancel,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams end=new LinearLayout.LayoutParams(0,dp(44),1);end.leftMargin=dp(10);buttons.addView(confirm,end);body.addView(buttons);
        cancel.setOnClickListener(v->dismiss());
        dialog.setContentView(body);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);applyTheme(theme);refresh();
    }
    private int dp(int n){return Math.round(context.getResources().getDisplayMetrics().density*n);}
    private TextView text(String value,int size){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);return view;}
    private GradientDrawable background(int color,int line,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setStroke(dp(1),line);b.setCornerRadius(dp(radius));return b;}
    private LinearLayout row(){
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);row.setBaselineAligned(false);row.setMinimumHeight(dp(48));
        // 所有列同时显示；操作和备注自动换行，表头与内容使用相同权重。
        for(int c=0;c<7;c++){TextView cell=text("",11);cell.setGravity(c==6?Gravity.CENTER_VERTICAL|Gravity.START:Gravity.CENTER);cell.setPadding(dp(3),dp(7),dp(3),dp(7));row.addView(cell,new LinearLayout.LayoutParams(0,-1,c==6?1.8f:c==0?1.1f:1f));}
        return row;
    }
    private void bind(LinearLayout view,int index){
        boolean isHeader=index<0,checked=!cellMode&&!isHeader&&selected.contains(table.rows.get(index).round);RecorderFollowTable.Row data=isHeader?null:table.rows.get(index);
        for(int c=0;c<7;c++){
            TextView cell=(TextView)view.getChildAt(c);String value;
            if(isHeader)value=c==0?"回合":c==6?"备注":table.agents[c-1];
            else value=c==0?(checked?"✓ ":"")+(data.label==null?data.round+"回合":data.label):c==6?data.note:data.cells[c-1];
            cell.setText(value==null?"":value);int fill=isHeader?theme.soft:index%2==0?theme.surface:theme.bg;
            // 保留原表操作底色；行模式标记回合列，格模式只勾选并描边该格。
            if(!isHeader&&c>0&&c<6&&data.colors[c-1]!=null&&!data.colors[c-1].equals("none"))fill=RecorderFollowTable.fill(data.colors[c-1]);
            cell.setTextColor(isHeader?theme.accent:c==0&&checked?theme.on:!isHeader&&c>0&&c<6&&data.colors[c-1]!=null&&!data.colors[c-1].equals("none")?0xff263536:theme.ink);
            cell.setTypeface(null,isHeader||c==0?Typeface.BOLD:Typeface.NORMAL);cell.setBackground(background(c==0&&checked?theme.accent:fill,theme.line,0));
            if(cellMode&&!isHeader&&c>0&&c<6){
                final int column=c-1,bit=1<<column;boolean active=(cells.getOrDefault(data.round,0)&bit)!=0;
                cell.setActivated(active);cell.setForeground(active?background(Color.TRANSPARENT,theme.accent,3):null);
                cell.setText((active?"✓ ":"")+(value==null?"":value));cell.setTag("condition-cell-"+index+"-"+column);
                cell.setContentDescription("第"+data.round+"回合第"+c+"位"+(active?"，已选":"，未选"));cell.setEnabled(data.round>0);
                cell.setOnClickListener(v->{int mask=cells.getOrDefault(data.round,0)^bit;if(mask==0)cells.remove(data.round);else cells.put(data.round,mask);refresh();});
            }
        }
        // ListView 会改写 selected 用于焦点；多选使用独立 activated 状态，续行标记不被覆盖。
        view.setActivated(checked);view.setForeground(checked?background(Color.TRANSPARENT,theme.accent,3):null);
        if(!isHeader){
            boolean available=data.round>0&&(availableRounds==null||availableRounds.contains(data.round));
            view.setTag("condition-row-"+index);view.setContentDescription(data.label+(available?(checked?"，已选":"，未选"):"，不可选"));view.setEnabled(available);view.setAlpha(available?1f:.4f);
            if(!cellMode)view.setOnClickListener(v->{if(!available)return;if(selected.contains(data.round))selected.remove(data.round);else {if(singleRound)selected.clear();selected.add(data.round);}refresh();});
        }
    }
    private void refresh(){int count=0;for(int mask:cells.values())count+=Integer.bitCount(mask);status.setText(cellMode?"已选 "+count+" 个回合密探位置":"已选 "+selected.size()+" 个回合");adapter.notifyDataSetChanged();}
    private final class Rows extends BaseAdapter {
        public int getCount(){return table.rows.size();}public Object getItem(int n){return table.rows.get(n);}public long getItemId(int n){return n;}
        public View getView(int n,View recycled,ViewGroup parent){LinearLayout view=recycled instanceof LinearLayout?(LinearLayout)recycled:row();bind(view,n);return view;}
    }
    void applyTheme(RecorderOverlayTheme value){
        theme=value;body.setBackground(background(theme.surface,theme.line,16));heading.setTextColor(theme.accent);hint.setTextColor(theme.muted);status.setTextColor(theme.muted);clear.setTextColor(theme.accent);
        tableContent.setWatermarkColor(theme.muted);
        for(int n=0;n<tableContent.getChildCount();n++){View child=tableContent.getChildAt(n);if("table-note".equals(child.getTag()))((TextView)child).setTextColor(theme.ink);}
        for(Button button:new Button[]{cancel,confirm}){boolean primary=button==confirm;button.setTextColor(primary?theme.on:theme.accent);button.setBackground(background(primary?theme.accent:theme.bg,primary?theme.accent:theme.line,12));button.setBackgroundTintList(null);button.setAllCaps(false);button.setTextSize(14);button.setStateListAnimator(null);button.setElevation(0);}
        bind(header,-1);adapter.notifyDataSetChanged();
    }
    void show(){dialog.show();android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();dialog.getWindow().setDimAmount(.32f);dialog.getWindow().setLayout(Math.min(metrics.widthPixels-dp(16),dp(720)),Math.min(metrics.heightPixels-dp(48),dp(680)));}
    void dismiss(){dialog.dismiss();}
}
