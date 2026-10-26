package com.local.yuanatlas;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** 只编辑草稿；整表选行确认后更新摘要，外层保存时才写入本表规则。 */
final class RecorderConditionSettingsView {
    private final Context context;private final RecorderFollowTable table;private final Dialog owner;
    private final Supplier<RecorderOverlayTheme> theme;private final RecorderRoundConditions draft;
    private final Map<String,TextView> summaries=new LinkedHashMap<>();
    private final List<LinearLayout> sections=new ArrayList<>();
    private RecorderFazhengSettingsView relics;
    private EditText count;private RecorderConditionRoundPicker picker;private RecorderDragonActionSettingsView actions;
    RecorderConditionSettingsView(Context context,LinearLayout parent,RecorderFollowTable table,RecorderRoundConditions value,Dialog owner,Supplier<RecorderOverlayTheme> theme){
        this.context=context;this.table=table;this.owner=owner;this.theme=theme;draft=value.copy();
        LinearLayout star=section(parent,"唤星检测");
        selection(star,"紫星","purple");selection(star,"橙星","orange");
        int column=RecorderRoundConditions.zhugeColumn(table);
        if(column>=0){
            LinearLayout dragon=section(parent,"龙气不足重开");
            LinearLayout number=new LinearLayout(context);number.setGravity(Gravity.CENTER_VERTICAL);number.addView(text("至少龙气数量（1–6）",13),new LinearLayout.LayoutParams(0,dp(44),1));
            count=new EditText(context);count.setSingleLine(true);count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);count.setText(String.valueOf(draft.dragonCount));count.setTextSize(14);count.setContentDescription("回合检查龙气数量");count.setPadding(dp(10),0,dp(10),0);number.addView(count,new LinearLayout.LayoutParams(dp(68),dp(40)));dragon.addView(number);
            selection(dragon,"选择回合","dragon");
            LinearLayout vein=section(parent,"检测龙脉内外重开");
            hint(vein,"选择各回合期望状态，不符时撤退；已开启循环录制则接着重开。未选择回合即关闭。");
            selection(vein,"要求龙脉内","vein-inside");selection(vein,"要求龙脉外","vein-outside");
        }else {draft.dragonRounds.clear();draft.veinTargets.clear();draft.dragonActionRounds.clear();draft.veinActionRounds.clear();} // 隐藏项不能留下看不见的检测条件。
        LinearLayout death=section(parent,"检测阵亡重开");selection(death,"选择单元格","death");
        if(column>=0){
            LinearLayout action=section(parent,"龙气 / 龙脉条件动作");
            // 简短交互提示放在入口上方，滑块与动作列表下方不再堆说明段落。
            hint(action,"点击 ＋ 新增配置块，再在块内选择回合 · 动作留空跳过");
            actions=new RecorderDragonActionSettingsView(context,action,table,draft,owner,theme);
        }
        if(RecorderFazhengCondition.column(table)>=0){
            LinearLayout red=section(parent,"圣物检测 · 法正");
            hint(red,"选中回合的首个 ↓ 自动连续下拉并检测，最多 5 次；新物品在上，旧物品从下流失。");
            selection(red,"选择回合","fazheng");
            relics=new RecorderFazhengSettingsView(context,red,draft.fazheng,theme);
            hint(red,"目标同时在当前槽位出现即命中，不限顺序或相邻；剩余次数无法凑齐时重开，未确认新增时停止跟打。");
            TextView actionTitle=text("圣物命中后",13);red.addView(actionTitle);
            RadioGroup choices=new RadioGroup(context);choices.setOrientation(RadioGroup.VERTICAL);red.addView(choices);
            for(int n=0;n<RecorderFazhengCondition.ACTIONS.length;n++){
                final String action=RecorderFazhengCondition.ACTIONS[n];RadioButton choice=new RadioButton(context);
                choice.setId(android.view.View.generateViewId());choice.setText(RecorderFazhengCondition.LABELS[n]);choice.setTextSize(13);choice.setContentDescription("圣物命中后"+RecorderFazhengCondition.LABELS[n]);choices.addView(choice);
                choice.setChecked(draft.fazheng.action.equals(action));choice.setOnCheckedChangeListener((button,checked)->{if(checked)draft.fazheng.action=action;});
            }
            hint(red,"↑ / ↓ / A 只操作法正一次后继续原表；第 5 次命中时额外 ↓ 跳过，避免超过上限。未选择回合即关闭。");
        }else draft.fazheng.rounds.clear();
        refresh();owner.setOnDismissListener(dialog->close());
    }
    private int dp(int n){return Math.round(context.getResources().getDisplayMetrics().density*n);}
    private LinearLayout section(LinearLayout parent,String title){LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(12),dp(12),dp(10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);parent.addView(panel,p);sections.add(panel);TextView label=text(title,14);label.setTag("settings-heading");label.setTypeface(null,Typeface.BOLD);label.setPadding(0,0,0,dp(6));panel.addView(label);return panel;}
    private TextView text(String label,int size){TextView t=new TextView(context);t.setText(label);t.setTextSize(size);return t;}
    private void heading(LinearLayout parent,String label){TextView title=text(label,15);title.setTag("settings-heading");title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(16),0,dp(8));parent.addView(title);}
    private void hint(LinearLayout parent,String label){TextView t=text(label,11);t.setTag("settings-hint");t.setPadding(0,0,0,dp(8));parent.addView(t);}
    private void selection(LinearLayout parent,String label,String key){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(4),0,dp(4));
        Button button=new Button(context);button.setText(label);button.setContentDescription(isVein(key)?"龙脉重开"+label+"选择回合":key.equals("fazheng")?"法正圣物检测选择回合":key.equals("death")?"检测阵亡重开选择单元格":key.equals("dragon")?"龙气检测选择回合":label+"选择回合");row.addView(button,new LinearLayout.LayoutParams(dp(isVein(key)?112:96),dp(42)));
        TextView summary=text("",12);summary.setTag("settings-hint");summary.setPadding(dp(12),dp(6),0,dp(6));summary.setContentDescription(key+"检测回合摘要");row.addView(summary,new LinearLayout.LayoutParams(0,-2,1));summaries.put(key,summary);
        button.setOnClickListener(v->open(key));summary.setOnClickListener(v->open(key));parent.addView(row);
    }
    static String describe(Set<Integer> selected){if(selected.isEmpty())return "未选择回合";StringJoiner result=new StringJoiner("、");for(int round:selected)result.add(String.valueOf(round));return "第 "+result+" 回合";}
    private static boolean isVein(String key){return key.equals("vein-inside")||key.equals("vein-outside");}
    private static int veinState(String key){return key.equals("vein-inside")?RecorderDragonVeinVision.INSIDE:RecorderDragonVeinVision.OUTSIDE;}
    private Set<Integer> selected(String key){return isVein(key)?draft.roundsForVein(veinState(key)):key.equals("fazheng")?draft.fazheng.rounds:key.equals("dragon")?draft.dragonRounds:draft.roundsForStar(key);}
    private void refresh(){for(Map.Entry<String,TextView> e:summaries.entrySet())e.getValue().setText(e.getKey().equals("death")?describeDeaths():describe(selected(e.getKey())));}
    private String describeDeaths(){
        if(draft.deathCells.isEmpty())return "未选择单元格";StringJoiner result=new StringJoiner("；");
        for(Map.Entry<Integer,Integer> entry:draft.deathCells.entrySet()){StringJoiner columns=new StringJoiner("、");for(int c=0;c<5;c++)if((entry.getValue()&(1<<c))!=0)columns.add(String.valueOf(c+1));result.add("第 "+entry.getKey()+" 回合："+columns+" 号位");}return result.toString();
    }
    private void open(String key){
        close();if(key.equals("death")){
            picker=new RecorderConditionRoundPicker(context,table,"检测阵亡重开",draft.deathCells,theme.get(),owner.getWindow().getAttributes().type,cells->{draft.deathCells.clear();draft.deathCells.putAll(cells);refresh();});picker.show();return;
        }
        String title=isVein(key)?"龙脉重开 · 要求"+RecorderDragonVeinVision.name(veinState(key)):key.equals("fazheng")?"法正圣物检测":key.equals("dragon")?"龙气检测":key.equals("purple")?"紫星检测":"橙星检测";
        Set<Integer> selected=selected(key);
        picker=new RecorderConditionRoundPicker(context,table,title,selected,theme.get(),owner.getWindow().getAttributes().type,rounds->{
            if(isVein(key)){draft.setVeinRounds(veinState(key),rounds);}else if(key.equals("fazheng")){draft.fazheng.rounds.clear();draft.fazheng.rounds.addAll(rounds);}else if(key.equals("dragon")){draft.dragonRounds.clear();draft.dragonRounds.addAll(rounds);}else draft.setStarRounds(key,rounds);refresh();
        });picker.show();
    }
    void applyTheme(){for(LinearLayout panel:sections){android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(theme.get().bg);bg.setCornerRadius(dp(16));panel.setBackground(bg);}if(picker!=null)picker.applyTheme(theme.get());if(actions!=null)actions.applyTheme();if(relics!=null)relics.applyTheme();}
    void close(){if(picker!=null){picker.dismiss();picker=null;}if(actions!=null)actions.close();}
    RecorderRoundConditions read(){RecorderRoundConditions value=draft.copy();if(count!=null)value.dragonCount=RecorderSettingsValidation.number(count,"至少龙气数量",1,6);if(actions!=null)actions.readInto(value);value.validate(table);return value;}
}
