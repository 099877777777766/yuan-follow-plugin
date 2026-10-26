package com.local.yuanatlas;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** 复用整表回合多选；编号未知也能开启保护，避免强制切敌期间写错原编号。 */
final class RecorderTauntSettingsView {
    private final Context context;private final RecorderFollowTable table;private final Dialog owner;
    private final Supplier<RecorderOverlayTheme> theme;private final Runnable restyle;
    private final Map<Integer,Integer> draft=new TreeMap<>();private final Map<Integer,EditText> inputs=new TreeMap<>();
    private final TextView summary;private final LinearLayout positions;private RecorderConditionRoundPicker picker;
    RecorderTauntSettingsView(Context context,LinearLayout parent,RecorderFollowTable table,RecorderFollowSettings settings,Dialog owner,Supplier<RecorderOverlayTheme> theme,Runnable restyle){
        this.context=context;this.table=table;this.owner=owner;this.theme=theme;this.restyle=restyle;draft.putAll(settings.tauntRounds);
        Button choose=new Button(context);choose.setText("选择嘲讽回合");choose.setContentDescription("选择嘲讽回合");parent.addView(choose,new LinearLayout.LayoutParams(-1,dp(44)));
        summary=new TextView(context);summary.setTag("settings-hint");summary.setTextSize(12);summary.setPadding(0,dp(6),0,dp(6));parent.addView(summary);
        TextView hint=new TextView(context);hint.setTag("settings-hint");hint.setTextSize(11);hint.setText("所选回合由游戏强制选敌，跟打只执行密探动作。未选回合恢复嘲讽前目标；连续嘲讽请选中每个受影响回合。目标填 0 表示未知，期间保留存活状态；知道编号时可填写 1–敌人数。");parent.addView(hint);
        positions=new LinearLayout(context);positions.setOrientation(LinearLayout.VERTICAL);parent.addView(positions);refresh();
        choose.setOnClickListener(v->{
            try{read(5);}catch(Exception error){RecorderSettingsValidation.report(context,error);return;}
            close();picker=new RecorderConditionRoundPicker(context,table,"嘲讽处理",new TreeSet<>(draft.keySet()),theme.get(),owner.getWindow().getAttributes().type,rounds->{
                draft.keySet().retainAll(rounds);for(int round:rounds)draft.putIfAbsent(round,0);refresh();restyle.run();
            });picker.show();
        });
    }
    private int dp(int n){return Math.round(context.getResources().getDisplayMetrics().density*n);}
    private void refresh(){
        summary.setText(RecorderConditionSettingsView.describe(draft.keySet()));positions.removeAllViews();inputs.clear();
        for(Map.Entry<Integer,Integer> entry:draft.entrySet()){
            LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label=new TextView(context);label.setText("第 "+entry.getKey()+" 回合嘲讽目标");label.setTextSize(12);row.addView(label,new LinearLayout.LayoutParams(0,dp(44),1));
            EditText input=new EditText(context);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(String.valueOf(entry.getValue()));input.setTextSize(14);input.setContentDescription("第"+entry.getKey()+"回合嘲讽目标");row.addView(input,new LinearLayout.LayoutParams(dp(64),dp(42)));positions.addView(row);inputs.put(entry.getKey(),input);
        }
    }
    Map<Integer,Integer> read(int enemies){
        Map<Integer,Integer> result=new TreeMap<>();for(Map.Entry<Integer,EditText> entry:inputs.entrySet())result.put(entry.getKey(),RecorderSettingsValidation.number(entry.getValue(),"第"+entry.getKey()+"回合嘲讽目标",0,enemies));
        draft.clear();draft.putAll(result);return result;
    }
    void close(){if(picker!=null){picker.dismiss();picker=null;}}
}
