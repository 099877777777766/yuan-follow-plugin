package com.local.yuanatlas;

import android.content.Context;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

/** 保留桥名称及备份格式；日常读写按主键访问 SQLite，完整快照仅用于角色管理和备份。 */
public final class RoleFiles {
    private final AppDatabase database;
    public RoleFiles(Context context) { database = AppDatabase.open(context); }
    private interface Action { Object run() throws Exception; }
    private String result(Action action) {
        try { Object value=action.run();return new JSONObject().put("ok",true).put("value",value==null?JSONObject.NULL:value).toString(); }
        catch(Exception e){try{return new JSONObject().put("ok",false).put("error",e.getMessage()==null?"数据库操作失败，未保存":e.getMessage()).toString();}catch(Exception ignored){return "{\"ok\":false}";}}
    }
    @JavascriptInterface public String migrationSource(){return result(database::migrationSource);}
    @JavascriptInterface public String migrate(String text){return result(()->{database.migrateRoles(text);return null;});}
    @JavascriptInterface public String cleanupLegacy(){return result(()->{database.cleanupRoles();return null;});}
    @JavascriptInterface public String read(){return result(database::readRoles);}
    @JavascriptInterface public String write(int revision,String text){return result(()->{database.writeRoles(revision,text);return null;});}
    @JavascriptInterface public String active(String game){return result(()->database.active(game));}
    @JavascriptInterface public String readData(String game,String id,String key){return result(()->database.readData(game,id,key));}
    @JavascriptInterface public String writeData(String game,String id,String key,String text){return result(()->{database.writeData(game,id,key,text);return null;});}
    @JavascriptInterface public String workspace(String game,String id,String key,String tableId){return result(()->database.workspace(game,id,key,tableId));}
    @JavascriptInterface public String workspaceRevision(String game,String id,String key,String tableId){return result(()->database.workspaceRevision(game,id,key,tableId));}
    @JavascriptInterface public String patchWorkspace(String game,String id,String key,String tableId,String text){return result(()->{database.patchWorkspace(game,id,key,tableId,text);return null;});}
    @JavascriptInterface public String saveWorkspace(String game,String id,String key,String tableId,String text){return result(()->{database.saveWorkspace(game,id,key,tableId,text);return null;});}
    @JavascriptInterface public String table(String game,String id,String key,String tableId){return result(()->database.table(game,id,key,tableId));}
    @JavascriptInterface public String tableList(String game,String id,String key){return result(()->database.tableList(game,id,key));}
    @JavascriptInterface public String saveTable(String game,String id,String key,String request){return result(()->database.saveTable(game,id,key,request));}
}
