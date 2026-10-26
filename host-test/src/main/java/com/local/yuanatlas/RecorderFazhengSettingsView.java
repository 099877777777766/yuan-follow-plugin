package com.local.yuanatlas;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** 原生圣物选择器：只写设置草稿；组合技可混搭，共用圣物合并为同一组目标。 */
final class RecorderFazhengSettingsView {
    private final Context context;private final RecorderFazhengCondition draft;private final Supplier<RecorderOverlayTheme> theme;
    private final LinearLayout root,selected,entries;private boolean combos;private final List<Button> tabs=new ArrayList<>();
    private final List<LinearLayout> comboCards=new ArrayList<>(),itemCards=new ArrayList<>();
    RecorderFazhengSettingsView(Context context,LinearLayout parent,RecorderFazhengCondition draft,Supplier<RecorderOverlayTheme> theme){
        this.context=context;this.draft=draft;this.theme=theme;root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);parent.addView(root);
        LinearLayout mode=row();TextView label=text("目标圣物 · 选择 1～3 种",14);mode.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Switch detail=new Switch(context);detail.setText(draft.detailed?"详细":"简略");detail.setContentDescription("圣物详细模式");detail.setChecked(draft.detailed);mode.addView(detail);root.addView(mode);
        selected=new LinearLayout(context);selected.setOrientation(LinearLayout.VERTICAL);root.addView(selected);
        LinearLayout tabRow=row();for(String title:new String[]{"圣物","组合技"}){Button tab=new Button(context);tab.setText(title);tab.setContentDescription("查看"+title);tabRow.addView(tab,new LinearLayout.LayoutParams(0,dp(44),1));tabs.add(tab);tab.setOnClickListener(v->{combos=title.equals("组合技");render();});}root.addView(tabRow);
        entries=new LinearLayout(context);entries.setOrientation(LinearLayout.VERTICAL);root.addView(entries);
        detail.setOnCheckedChangeListener((button,checked)->{draft.detailed=checked;detail.setText(checked?"详细":"简略");render();});render();
    }
    private int dp(int value){return Math.round(context.getResources().getDisplayMetrics().density*value);}
    private TextView text(String value,int size){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(theme.get().ink);return t;}
    private LinearLayout row(){LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(4),0,dp(4));return row;}
    private ImageView icon(int id,int size){ImageView v=new ImageView(context);v.setImageResource(RecorderFazhengRelics.ICONS[id]);android.graphics.drawable.GradientDrawable circle=new android.graphics.drawable.GradientDrawable();circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);circle.setColor(theme.get().bg);v.setBackground(circle);v.setClipToOutline(true);v.setContentDescription(RecorderFazhengRelics.NAMES[id]+"图标");v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    private void render(){
        selected.removeAllViews();LinearLayout header=row();TextView count=text("已选 "+draft.targets.size()+" / 3",12);count.setPadding(0,0,dp(8),0);header.addView(count);
        // 已选目标只显示一行图标；名称保留给读屏和长按提示，不再占据纵向空间。
        LinearLayout selectedIcons=row();for(int id:draft.targets){ImageView image=icon(id,30);if(android.os.Build.VERSION.SDK_INT>=26)image.setTooltipText(RecorderFazhengRelics.NAMES[id]);LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)image.getLayoutParams();params.rightMargin=dp(6);selectedIcons.addView(image);}header.addView(selectedIcons,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton clear=new ImageButton(context);clear.setImageResource(R.drawable.recorder_round_close);clear.setBackgroundColor(android.graphics.Color.TRANSPARENT);clear.setPadding(dp(10),dp(10),dp(10),dp(10));clear.setContentDescription("清空圣物目标");header.addView(clear,new LinearLayout.LayoutParams(dp(44),dp(44)));clear.setOnClickListener(v->{draft.targets.clear();render();});selected.addView(header);
        entries.removeAllViews();comboCards.clear();itemCards.clear();
        if(combos){TextView hint=text("组合技可多选，共用圣物只计一种，合计最多 3 种。",12);hint.setTag("relic-effect");hint.setPadding(0,dp(6),0,0);entries.addView(hint);for(int i=0;i<RecorderFazhengRelics.COMBOS.length;i++)combo(i);}else{for(int i=0;i<RecorderFazhengRelics.NAMES.length;i++)item(i);}
        applyTheme();
    }
    private void item(int id){
        // 图标、名称和详细效果属于同一点击区域，整张卡片切换选择，不再显示勾选框。
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(8),dp(12),dp(8));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);entries.addView(card,params);itemCards.add(card);
        LinearLayout line=row();line.addView(icon(id,40));TextView name=text(RecorderFazhengRelics.NAMES[id],13);name.setPadding(dp(8),0,dp(4),0);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));card.addView(line);
        card.setContentDescription("选择"+RecorderFazhengRelics.NAMES[id]);card.setFocusable(true);
        card.setOnClickListener(v->{if(!v.isEnabled())return;if(draft.targets.contains(id))draft.targets.remove(Integer.valueOf(id));else if(draft.targets.size()<3)draft.targets.add(id);render();});
        if(draft.detailed){TextView description=text(RecorderFazhengRelics.EFFECTS[id],12);description.setTag("relic-effect");description.setPadding(0,dp(4),0,dp(4));description.setLineSpacing(dp(4),1);card.addView(description);}
    }
    private void combo(int index){
        // 每个组合的标题、素材和效果都收进同一张卡片，滚动时不会串到相邻组合。
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(10);entries.addView(card,cardParams);comboCards.add(card);
        LinearLayout heading=row();heading.setPadding(0,0,0,dp(6));
        TextView title=text("【"+RecorderFazhengRelics.COMBOS[index]+"】",14);title.setContentDescription(RecorderFazhengRelics.COMBOS[index]);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setLineSpacing(dp(3),1);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(heading);card.setContentDescription("选择组合技"+RecorderFazhengRelics.COMBOS[index]);card.setFocusable(true);
        card.setOnClickListener(v->{if(!v.isEnabled())return;toggleCombo(index,!comboSelected(index));render();});
        // 沿用游戏图鉴的“图标＋名称”配方排版；每件圣物整体换行，避免图标和名字分离。
        RelicFormula formula=new RelicFormula(context);int position=0;
        for(int id:RecorderFazhengRelics.TARGETS[index]){
            LinearLayout ingredient=row();ingredient.setPadding(0,dp(3),dp(4),dp(3));
            if(position++>0){TextView plus=text("＋",12);plus.setPadding(0,0,dp(4),0);ingredient.addView(plus);}
            ingredient.addView(icon(id,20));TextView name=text(RecorderFazhengRelics.NAMES[id],12);name.setTag("relic-name-"+id);name.setPadding(dp(4),0,0,0);ingredient.addView(name);formula.addView(ingredient);
        }
        card.addView(formula,new LinearLayout.LayoutParams(-1,-2));
        if(draft.detailed){
            // 详细模式的效果紧跟配方，仍归属当前组合。
            TextView description=text(RecorderFazhengRelics.COMBO_EFFECTS[index],12);description.setTag("relic-effect");description.setLineSpacing(dp(4),1);description.setPadding(0,dp(6),0,0);card.addView(description);
        }
    }
    /** 配方按完整圣物换行，测量和布局使用同一宽度规则，适配窄屏与放大字体。 */
    private final class RelicFormula extends ViewGroup {
        RelicFormula(Context context){super(context);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),x=0,y=0,lineHeight=0;
            for(int n=0;n<getChildCount();n++){View child=getChildAt(n);child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));int w=child.getMeasuredWidth(),h=child.getMeasuredHeight();if(x>0&&x+w>width){y+=lineHeight;x=0;lineHeight=0;}x+=w;lineHeight=Math.max(lineHeight,h);}
            setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(y+lineHeight,heightSpec));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            int width=right-left,x=0,y=0,lineHeight=0;
            for(int n=0;n<getChildCount();n++){View child=getChildAt(n);int w=child.getMeasuredWidth(),h=child.getMeasuredHeight();if(x>0&&x+w>width){y+=lineHeight;x=0;lineHeight=0;}child.layout(x,y,x+w,y+h);x+=w;lineHeight=Math.max(lineHeight,h);}
        }
    }
    private android.graphics.drawable.GradientDrawable rounded(int color,int stroke,int radius){
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(color);background.setCornerRadius(dp(radius));background.setStroke(dp(1),stroke);return background;
    }
    private boolean comboSelected(int index){
        // 同一组目标可同时满足多个组合；用包含关系恢复选中状态，无需另存组合编号。
        for(int id:RecorderFazhengRelics.TARGETS[index])if(!draft.targets.contains(id))return false;return true;
    }
    private Set<Integer> comboUnion(int index){
        Set<Integer> merged=new LinkedHashSet<>(draft.targets);for(int id:RecorderFazhengRelics.TARGETS[index])merged.add(id);return merged;
    }
    private void toggleCombo(int index,boolean checked){
        if(checked){
            // 先按种类去重再核对三槽上限，超限时不能修改已有目标。
            Set<Integer> merged=comboUnion(index);if(merged.size()>3)return;draft.targets.clear();draft.targets.addAll(merged);
        }else{
            // 取消一个组合时，仅移除它独占的圣物，保留其他已选组合依赖的共用圣物。
            Set<Integer> shared=new HashSet<>();for(int n=0;n<RecorderFazhengRelics.TARGETS.length;n++)if(n!=index&&comboSelected(n))for(int id:RecorderFazhengRelics.TARGETS[n])shared.add(id);
            for(int id:RecorderFazhengRelics.TARGETS[index])if(!shared.contains(id))draft.targets.remove(Integer.valueOf(id));
        }
    }
    void applyTheme(){tint(root);
        for(int i=0;i<itemCards.size();i++){boolean active=draft.targets.contains(i);styleCard(itemCards.get(i),active,active||draft.targets.size()<3);}
        for(int i=0;i<comboCards.size();i++){boolean active=comboSelected(i);styleCard(comboCards.get(i),active,active||comboUnion(i).size()<=3);}

        for(int i=0;i<tabs.size();i++){Button tab=tabs.get(i);boolean active=combos==(i==1);tab.setSelected(active);tab.setTextColor(active?theme.get().on:theme.get().ink);tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active?theme.get().accent:theme.get().soft));}}
    private void styleCard(View card,boolean selected,boolean enabled){
        card.setSelected(selected);card.setEnabled(enabled);card.setAlpha(enabled?1f:.55f);card.setBackground(rounded(selected?theme.get().soft:theme.get().surface,selected?theme.get().accent:theme.get().line,12));
    }
    private void tint(View v){if(v instanceof ImageButton)((ImageButton)v).setColorFilter(theme.get().muted);if("combo-divider".equals(v.getTag()))v.setBackgroundColor(theme.get().line);if(v instanceof TextView)((TextView)v).setTextColor("relic-effect".equals(v.getTag())?theme.get().muted:theme.get().ink);if(v instanceof TextView&&v.getTag() instanceof String&&((String)v.getTag()).startsWith("relic-name-")){int id=Integer.parseInt(((String)v.getTag()).substring(11));int group=id<3?0:id<6?1:id<10?2:3;((TextView)v).setTextColor(new int[]{0xff426985,0xff80548e,0xff8b641d,0xffae4352}[group]);}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tint(((ViewGroup)v).getChildAt(i));}
}
