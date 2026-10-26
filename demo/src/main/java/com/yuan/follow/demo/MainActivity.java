package com.yuan.follow.demo;
import android.app.Activity;import android.os.Bundle;import android.widget.*;import com.local.yuanatlas.YuanFollow;
/** 最小 Android 宿主，只演示公开插件 API，不包含原 App 业务。 */
public final class MainActivity extends Activity {
 private TextView status; private EditText game,table;private YuanFollow.Listener listener;
 @Override public void onCreate(Bundle state){super.onCreate(state);
 LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(24,32,24,24);setContentView(body);
 status=new TextView(this);body.addView(status);game=new EditText(this);game.setHint("目标游戏包名（如 com.example.game）");body.addView(game);
 table=new EditText(this);table.setHint("粘贴结构化表 JSON 或 YRAF1 跟打码");table.setMaxLines(4);body.addView(table);
 button(body,"开启无障碍服务",()->YuanFollow.openAccessibilitySettings(this));
 button(body,"选择带码图片",()->YuanFollow.chooseImage(this,game.getText().toString().trim(),this::result));
 button(body,"加载表或跟打码",()->{String data=table.getText().toString();String target=game.getText().toString().trim();if(data.startsWith("YRAF1:"))YuanFollow.openCode(this,target,data,this::result);else YuanFollow.openTable(this,target,data,this::result);});
 button(body,"暂停跟打",YuanFollow::pause);button(body,"关闭悬浮窗",YuanFollow::close);
 button(body,"运行诊断",()->new android.app.AlertDialog.Builder(this).setMessage(YuanFollow.diagnostics(this)).setPositiveButton("关闭",null).show());
 listener=(type,message)->status.setText(type+"："+message);YuanFollow.addListener(listener);
 }
 private void result(boolean ok,String code,String message){status.setText(code+"："+message);}
 private void button(LinearLayout body,String title,Runnable action){Button button=new Button(this);button.setText(title);body.addView(button);button.setOnClickListener(v->action.run());}
 @Override public void onResume(){super.onResume();status.setText("插件连接："+YuanFollow.status(this));}
 @Override public void onDestroy(){YuanFollow.removeListener(listener);super.onDestroy();}
}
