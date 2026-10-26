package com.local.yuanatlas;

import android.content.Context;
import android.graphics.Color;
import org.json.JSONObject;

/** 网页主题的原生只读副本；缓存使无障碍服务重启后仍使用最近一次已保存的主题。 */
final class RecorderOverlayTheme {
    private static final String STORE="yuan-recorder-overlay-theme",KEY="palette";
    private static final String DEFAULT="{\"accent\":\"#337764\",\"ink\":\"#223d36\",\"muted\":\"#657c74\",\"surface\":\"#ffffff\",\"soft\":\"#deede7\",\"bg\":\"#edf3f1\",\"line\":\"#d7e3df\",\"tableLine\":\"#9bb9af\",\"on\":\"#ffffff\",\"stripe\":\"#d2e2dc\"}";
    final int accent,ink,muted,surface,soft,bg,line,tableLine,on,stripe;
    private static int color(JSONObject data,String key)throws Exception{
        String value=data.getString(key);
        if(!value.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("主题颜色无效");
        return Color.parseColor(value);
    }
    RecorderOverlayTheme(String raw)throws Exception{
        if(raw==null||raw.length()>2048)throw new IllegalArgumentException("主题数据无效");
        JSONObject data=new JSONObject(raw);
        accent=color(data,"accent");ink=color(data,"ink");muted=color(data,"muted");surface=color(data,"surface");soft=color(data,"soft");
        bg=color(data,"bg");line=color(data,"line");tableLine=color(data,"tableLine");on=color(data,"on");stripe=color(data,"stripe");
    }
    static RecorderOverlayTheme load(Context context){
        try{return new RecorderOverlayTheme(context.getSharedPreferences(STORE,0).getString(KEY,DEFAULT));}
        catch(Exception ignored){try{return new RecorderOverlayTheme(DEFAULT);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    }
    static RecorderOverlayTheme save(Context context,String raw)throws Exception{
        RecorderOverlayTheme theme=new RecorderOverlayTheme(raw); // 验证完整数据后才覆盖，不能留下半套配色。
        context.getSharedPreferences(STORE,0).edit().putString(KEY,raw).apply();return theme;
    }
}
