package com.local.yuanatlas;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** 每行绑定原始行动位；三色块可取消选择，留空跳过，原序号不改变。 */
final class RecorderDragonActionSettingsView {
    private final Context context;private final RecorderFollowTable table;private final Supplier<RecorderOverlayTheme> theme;
    private final LinearLayout roundsBody;
    private final List<LinearLayout> cards=new ArrayList<>();
    private final RecorderRoundConditions draft;private final Set<Integer> selected=new TreeSet<>();
    private final Dialog owner;private RecorderConditionRoundPicker picker;private final TextView add;
    // 块先于回合存在；未选回合的草稿必须选好或删除后才能保存。
    private static final class Block {int round,lower=0,upper=2;boolean dragon=true,vein;View anchor;RecorderDragonRangeSlider slider;}
    private final List<Block> blocks=new ArrayList<>();
    private final List<SlotView> views=new ArrayList<>();private final Map<Integer,TextView> headings=new TreeMap<>();
    private final List<Runnable> conditionLabels=new ArrayList<>();
    RecorderDragonActionSettingsView(Context context,LinearLayout parent,RecorderFollowTable table,RecorderRoundConditions rules,Dialog owner,Supplier<RecorderOverlayTheme> theme){
        this.context=context;this.table=table;this.theme=theme;this.owner=owner;draft=rules.copy();
        add=text("＋",26);add.setGravity(Gravity.CENTER);add.setContentDescription("新增龙气龙脉配置块");add.setFocusable(true);
        LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(dp(44),dp(44));addParams.gravity=Gravity.END;parent.addView(add,addParams);
        add.setOnClickListener(v->{
            Block block=new Block();blocks.add(block);renderRounds();
            // 长设置页中自动露出新块的选择入口，不让新增内容藏在已有动作列表下方。
            block.anchor.post(()->block.anchor.requestRectangleOnScreen(new android.graphics.Rect(0,0,block.anchor.getWidth(),dp(160)),true));
        });
        roundsBody=new LinearLayout(context);roundsBody.setOrientation(LinearLayout.VERTICAL);parent.addView(roundsBody);
        for(int round:rules.actionRounds()){Block block=new Block();block.round=round;block.lower=draft.dragonActions.minimum(round);block.upper=draft.dragonActions.maximum(round);block.dragon=draft.dragonActionRounds.contains(round);block.vein=draft.veinActionRounds.contains(round);blocks.add(block);}
        selected.addAll(rules.actionRounds());draft.prepareActions(table,selected);renderRounds();
    }
    private int dp(int n){return Math.round(context.getResources().getDisplayMetrics().density*n);}
    private TextView text(String value,int size){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);return v;}
    private GradientDrawable bg(int fill,int stroke,int width,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setStroke(dp(width),stroke);d.setCornerRadius(dp(radius));return d;}
    /** 改选回合时清除旧行动位，避免把另一回合的操作套到不同的出手序号上。 */
    private void clearRound(int round){
        selected.remove(round);draft.dragonActionRounds.remove(round);draft.veinActionRounds.remove(round);
        draft.dragonActions.ranges.remove(round);draft.dragonActions.rounds.remove(round);draft.veinActions.rounds.remove(round);
        for(boolean inside:new boolean[]{true,false})draft.combinations.at(inside).rounds.remove(round);
    }
    private void chooseRound(Block block){
        close();Set<Integer> available=RecorderRoundConditions.rounds(table);
        available.removeIf(round->(selected.contains(round)&&round!=block.round)||RecorderDragonActionCondition.slots(table,round).isEmpty());
        picker=new RecorderConditionRoundPicker(context,table,"龙气 / 龙脉条件动作",block.round,available,theme.get(),owner.getWindow().getAttributes().type,rounds->{
            int round=rounds.isEmpty()?0:rounds.iterator().next();
            if(round!=block.round){clearRound(block.round);block.round=round;if(round>0){selected.add(round);if(block.dragon)draft.dragonActionRounds.add(round);if(block.vein)draft.veinActionRounds.add(round);draft.prepareActions(table,Collections.singleton(round));draft.dragonActions.range(round,block.lower,block.upper);}}
            renderRounds();
        });picker.show();
    }
    void close(){if(picker!=null){picker.dismiss();picker=null;}}
    private void renderRounds(){
        views.clear();headings.clear();cards.clear();conditionLabels.clear();roundsBody.removeAllViews();
        for(Block block:blocks){
            int round=block.round,index=blocks.indexOf(block)+1;block.slider=null;
            LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(10));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(12);roundsBody.addView(card,cardParams);cards.add(card);
            LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);card.addView(header);
            Button choose=new Button(context);choose.setText(round>0?"第 "+round+" 回合 ▾":"选择回合");choose.setTag("settings-round-button");choose.setContentDescription("配置块"+index+"选择回合");choose.setOnClickListener(v->chooseRound(block));block.anchor=choose;header.addView(choose,new LinearLayout.LayoutParams(0,dp(44),1));
            TextView remove=text("×",24);remove.setTag("settings-heading");remove.setGravity(Gravity.CENTER);remove.setContentDescription("删除配置块"+index);remove.setFocusable(true);header.addView(remove,new LinearLayout.LayoutParams(dp(44),dp(44)));
            remove.setOnClickListener(v->{clearRound(block.round);blocks.remove(block);renderRounds();});
            if(round==0){
                LinearLayout toggles=new LinearLayout(context);card.addView(toggles);conditionToggle(toggles,block,"龙气数量",true);conditionToggle(toggles,block,"龙脉背景",false);rangeControl(card,block);
                TextView pending=text("请先选择回合，再设置该回合的动作",12);pending.setTag("settings-hint");pending.setPadding(0,dp(8),0,0);card.addView(pending);continue;
            }
            List<RecorderFollowTable.Step> slots=RecorderDragonActionCondition.slots(table,round);
            TextView title=text("第 "+round+" 回合 · "+slots.size()+" 次诸葛亮行动",14);title.setTypeface(null,Typeface.BOLD);title.setTag("settings-heading");title.setPadding(0,0,0,dp(6));card.addView(title);headings.put(round,title);
            if(slots.isEmpty()){TextView error=text("本回合没有诸葛亮行动，请取消该回合选择",12);error.setTextColor(0xffb3261e);card.addView(error);continue;}
            LinearLayout toggles=new LinearLayout(context);card.addView(toggles);
            conditionToggle(toggles,block,"龙气数量",true);conditionToggle(toggles,block,"龙脉背景",false);
            rangeControl(card,block);
            boolean both=draft.joint(round);
            if(both){
                // 同时识别两项，每一种组合独立选择动作，不再设置检测优先级。
                for(boolean inVein:new boolean[]{true,false})for(boolean inRange:new boolean[]{true,false})
                    branch(card,round,inVein?"joint-inside":"joint-outside",inRange,slots,draft.combinations.actions(round,inVein,inRange));
            }else{
                String kind=draft.dragonActionRounds.contains(round)?"dragon":"vein";
                if(draft.actionRequired(round)){RecorderDragonActionCondition.Branches branches=(kind.equals("dragon")?draft.dragonActions:draft.veinActions).rounds.get(round);branch(card,round,kind,true,slots,branches.matched);branch(card,round,kind,false,slots,branches.unmatched);}
            }
        }
        applyTheme();
    }
    /** 滑块属于配置块；未选回合也可编辑，改选回合时范围随块保留。 */
    private void rangeControl(LinearLayout card,Block block){
        if(!block.dragon)return;
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);card.addView(header);
        TextView label=text("龙气范围",13);label.setTag("settings-heading");header.addView(label,new LinearLayout.LayoutParams(0,dp(32),1));
        TextView value=text(block.lower+"–"+block.upper,16);value.setTag("settings-heading");value.setTypeface(null,Typeface.BOLD);header.addView(value);
        block.slider=new RecorderDragonRangeSlider(context,block.lower,block.upper,theme,(lower,upper)->{
            block.lower=lower;block.upper=upper;value.setText(lower+"–"+upper);
            if(block.round>0)draft.dragonActions.range(block.round,lower,upper);
            refreshConditionLabels();
        });
        block.slider.setContentDescription("配置块"+(blocks.indexOf(block)+1)+"龙气范围");
        card.addView(block.slider,new LinearLayout.LayoutParams(-1,dp(66)));
    }
    private void conditionToggle(LinearLayout parent,Block block,String label,boolean dragon){
        CheckBox box=new CheckBox(context);box.setText(label);box.setTextSize(13);box.setTextColor(theme.get().ink);box.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.get().accent));box.setContentDescription((block.round>0?"第"+block.round+"回合":"配置块"+(blocks.indexOf(block)+1))+"启用"+label);box.setChecked(dragon?block.dragon:block.vein);parent.addView(box,new LinearLayout.LayoutParams(0,dp(44),1));
        box.setOnCheckedChangeListener((button,checked)->{if(dragon)block.dragon=checked;else block.vein=checked;if(block.round>0){Set<Integer> enabled=dragon?draft.dragonActionRounds:draft.veinActionRounds;if(checked)enabled.add(block.round);else enabled.remove(block.round);}renderRounds();});
    }
    /** 龙气范围使用包含端点的区间；组合标题同时标出龙脉状态。 */
    private String conditionLabel(int round,String source,boolean matched){
        int lower=draft.dragonActions.minimum(round),upper=draft.dragonActions.maximum(round);
        String inside="龙气 "+lower+"–"+upper;
        String outside=lower==0?"龙气超过 "+upper:upper==6?"龙气少于 "+lower:"龙气不在 "+lower+"–"+upper;
        if(!matched&&lower==0&&upper==6)outside="不会触发（龙气已选 0–6）";
        if(source.equals("vein"))return matched?"龙脉内":"龙脉外";
        String prefix=source.equals("joint-inside")?"龙脉内，且":source.equals("joint-outside")?"龙脉外，且":"";
        return prefix+(matched?inside:outside);
    }
    // 拖动范围只刷新标题和无障碍描述，保留四组动作与滚动位置。
    private void refreshConditionLabels(){for(Runnable update:conditionLabels)update.run();}
    private void branch(LinearLayout card,int round,String kind,boolean matched,List<RecorderFollowTable.Step> slots,List<String> actions){
        String label=conditionLabel(round,kind,matched);List<SlotView> branchViews=new ArrayList<>();
        TextView heading=text(label,12);heading.setTag("settings-heading");heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(12),0,dp(4));card.addView(heading);
        int row=-1;
        for(int i=0;i<slots.size();i++){
            RecorderFollowTable.Step step=slots.get(i);
            if(step.row!=row){row=step.row;if(table.rows.get(row).continuation){TextView source=text(table.rows.get(row).label,10);source.setTag("settings-hint");source.setSingleLine(true);source.setEllipsize(android.text.TextUtils.TruncateAt.END);source.setPadding(0,dp(4),0,dp(2));card.addView(source);}}
            SlotView view=new SlotView(round,label,i,step,actions);views.add(view);branchViews.add(view);card.addView(view);
        }
        conditionLabels.add(()->{String current=conditionLabel(round,kind,matched);heading.setText(current);for(SlotView view:branchViews)view.updateLabel(current);});
    }
    private final class SlotView extends LinearLayout {
        final TextView order,error;final TextView[] choices=new TextView[RecorderDragonActionCondition.ACTIONS.length];final List<String> actions;final int index,round;final RecorderFollowTable.Step step;String label;
        SlotView(int round,String branch,int index,RecorderFollowTable.Step step,List<String> actions){
            super(context);this.index=index;this.round=round;this.actions=actions;this.step=step;label=table.rows.get(step.row).label+"「"+branch+"」第 "+step.order+" 步";
            setOrientation(VERTICAL);setPadding(0,dp(3),0,dp(3));setContentDescription(label+"选择操作");
            LinearLayout line=new LinearLayout(context);line.setGravity(Gravity.CENTER_VERTICAL);addView(line);
            order=text(String.valueOf(step.order),17);order.setTypeface(null,Typeface.BOLD);order.setGravity(Gravity.CENTER);line.addView(order,new LinearLayout.LayoutParams(dp(28),dp(42)));
            error=text("操作无效，请选择 ↑、↓、A 或留空",11);error.setTextColor(0xffb3261e);error.setPadding(dp(50),dp(3),0,0);error.setVisibility(GONE);
            for(int n=0;n<choices.length;n++){
                String action=RecorderDragonActionCondition.ACTIONS[n];TextView choice=text(action,18);choices[n]=choice;
                choice.setGravity(Gravity.CENTER);choice.setTypeface(null,Typeface.BOLD);choice.setContentDescription(label+"选择"+action);choice.setFocusable(true);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(42),1);p.leftMargin=dp(6);line.addView(choice,p);
                // 再点已选色块即清空，不另外增加“不操作”按钮。
                choice.setOnClickListener(v->{actions.set(index,action.equals(actions.get(index))?"":action);error.setVisibility(GONE);paint();});
            }
            addView(error);
        }
        void updateLabel(String branch){label=table.rows.get(step.row).label+"「"+branch+"」第 "+step.order+" 步";setContentDescription(label+"选择操作");for(int n=0;n<choices.length;n++)choices[n].setContentDescription(label+"选择"+RecorderDragonActionCondition.ACTIONS[n]);}
        void paint(){
            RecorderOverlayTheme t=theme.get();order.setTextColor(t.ink);error.setTextColor(0xffb3261e);
            int[] fills={0xffdeede7,0xffe4ebfa,0xffffefc2},edges={0xff28745f,0xff3e649b,0xff87641a};
            for(int n=0;n<choices.length;n++){
                boolean checked=RecorderDragonActionCondition.ACTIONS[n].equals(actions.get(index));TextView choice=choices[n];choice.setSelected(checked);choice.setActivated(checked);
                choice.setTextColor(checked?Color.WHITE:edges[n]);choice.setBackground(bg(checked?edges[n]:fills[n],checked?edges[n]:t.line,checked?2:1,10));
                if(android.os.Build.VERSION.SDK_INT>=30)choice.setStateDescription(checked?"已选择":"未选择");
            }
            setBackground(bg(Color.TRANSPARENT,error.getVisibility()==VISIBLE?0xffb3261e:Color.TRANSPARENT,1,10));
        }
        void validateChoice(){if(!RecorderDragonActionCondition.validAction(actions.get(index))){error.setVisibility(VISIBLE);paint();throw new RecorderSettingsValidation(this,"请配置"+label+"动作");}}
    }
    void applyTheme(){
        for(LinearLayout card:cards){card.setBackground(bg(theme.get().surface,theme.get().line,1,14));paintLabels(card);}
        add.setTextColor(theme.get().accent);add.setBackground(bg(theme.get().surface,theme.get().line,1,12));
        for(Block block:blocks)if(block.slider!=null)block.slider.invalidate();if(picker!=null)picker.applyTheme(theme.get());
        for(SlotView view:views)view.paint();
    }
    private void paintLabels(View view){if("settings-round-button".equals(view.getTag())){TextView button=(TextView)view;button.setTextColor(theme.get().accent);button.setBackground(bg(theme.get().bg,theme.get().line,1,10));button.setBackgroundTintList(null);button.setTextSize(14);button.setElevation(0);button.setStateListAnimator(null);}if(view instanceof CompoundButton){((CompoundButton)view).setButtonTintList(android.content.res.ColorStateList.valueOf(theme.get().accent));((CompoundButton)view).setTextColor(theme.get().ink);}if(view instanceof TextView){if("settings-heading".equals(view.getTag()))((TextView)view).setTextColor(theme.get().accent);if("settings-hint".equals(view.getTag()))((TextView)view).setTextColor(theme.get().muted);}if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)paintLabels(((ViewGroup)view).getChildAt(n));}
    void readInto(RecorderRoundConditions value){
        for(Block block:blocks)if(block.round==0)throw new RecorderSettingsValidation(block.anchor,"请为新增配置块选择回合，或删除该块");
        if(!selected.isEmpty()){
            for(Block block:blocks)draft.dragonActions.range(block.round,block.lower,block.upper);
            for(int round:selected){
                if(RecorderDragonActionCondition.slots(table,round).isEmpty())throw new RecorderSettingsValidation(headings.get(round),"第 "+round+" 回合没有诸葛亮行动，请取消该回合选择");
                if(!draft.actionRequired(round))throw new RecorderSettingsValidation(headings.get(round),"请为第 "+round+" 回合选择龙气数量或龙脉背景条件");
                // 空动作有效；仅损坏的动作值按屏幕顺序定位，数量/条件设置仍须有效。
                for(SlotView view:views)if(view.round==round)view.validateChoice();
                draft.validateActions(table,round);
            }
        }
        value.dragonActionRounds.clear();value.dragonActionRounds.addAll(draft.dragonActionRounds);value.veinActionRounds.clear();value.veinActionRounds.addAll(draft.veinActionRounds);
        value.dragonActions=draft.dragonActions.copy();value.dragonActions.ranges.keySet().retainAll(selected);value.dragonActions.rounds.keySet().retainAll(value.dragonActionRounds);
        value.veinActions=draft.veinActions.copy();value.veinActions.rounds.keySet().retainAll(value.veinActionRounds);
        value.actionPriority.clear();value.jointActions.clear();value.combinations=draft.combinations.copy();
        for(boolean inVein:new boolean[]{true,false})value.combinations.at(inVein).rounds.keySet().retainAll(selected);
    }
}
