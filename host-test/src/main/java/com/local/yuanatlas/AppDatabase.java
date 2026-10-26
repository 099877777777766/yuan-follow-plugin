package com.local.yuanatlas;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 本地权威数据库。常用读写只访问当前模块/表；完整 JSON 仅用于迁移、备份和角色管理。 */
final class AppDatabase {
    private static final Map<String, AppDatabase> INSTANCES = new HashMap<>();
    private final SQLiteOpenHelper helper;
    private final File files;
    private static final String[] GAMES = {"codeYuan", "ruYuan"};
    static synchronized AppDatabase open(Context context) {
        File files = context.getFilesDir();
        String path = new File(files, "database/yuan.db").getAbsolutePath();
        AppDatabase found = INSTANCES.get(path);
        if (found == null) { found = new AppDatabase(context, files, path); INSTANCES.put(path, found); }
        return found;
    }
    private AppDatabase(Context context, File files, String path) {
        this.files = files;
        File folder = new File(path).getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("数据库目录无法创建");
        helper = new SQLiteOpenHelper(context, path, null, 1) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE meta (name TEXT PRIMARY KEY, json TEXT NOT NULL)");
                db.execSQL("CREATE TABLE roles (owner TEXT PRIMARY KEY, position INTEGER NOT NULL, json TEXT NOT NULL)");
                db.execSQL("CREATE TABLE items (owner TEXT NOT NULL, item_key TEXT NOT NULL, json TEXT NOT NULL, is_library INTEGER NOT NULL, PRIMARY KEY(owner,item_key))");
                db.execSQL("CREATE TABLE libraries (owner TEXT NOT NULL, item_key TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(owner,item_key))");
                db.execSQL("CREATE TABLE documents (owner TEXT NOT NULL,item_key TEXT NOT NULL,workspace TEXT NOT NULL,table_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(owner,item_key,workspace,table_id))");
                db.execSQL("CREATE TABLE states (owner TEXT NOT NULL,item_key TEXT NOT NULL,workspace TEXT NOT NULL,table_id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(owner,item_key,workspace,table_id))");
                db.execSQL("CREATE TABLE parts (owner TEXT NOT NULL,item_key TEXT NOT NULL,workspace TEXT NOT NULL,table_id TEXT NOT NULL,kind TEXT NOT NULL,part_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(owner,item_key,workspace,table_id,kind,part_id))");
                db.execSQL("CREATE TABLE catalog_games (game TEXT PRIMARY KEY,json TEXT NOT NULL)");
                db.execSQL("CREATE TABLE agents (game TEXT NOT NULL,agent_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(game,agent_id))");
            }
            @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
                // 未来版本必须显式编写升级，禁止删除用户数据库重建。
                throw new IllegalStateException("数据库版本不支持，请更新应用");
            }
        };
    }
    private SQLiteDatabase db() { return helper.getWritableDatabase(); }
    private interface Work<T> { T run() throws Exception; }
    private <T> T transaction(Work<T> work) throws Exception {
        SQLiteDatabase db = db(); db.beginTransaction();
        try { T value = work.run(); db.setTransactionSuccessful(); return value; }
        finally { db.endTransaction(); }
    }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static JSONObject without(JSONObject value, String... keys) throws Exception {
        JSONObject next = copy(value); for (String key : keys) next.remove(key); return next;
    }
    private static Object parse(String text) throws Exception { return new JSONTokener(text).nextValue(); }
    private static String encode(Object value) { return value == null || value == JSONObject.NULL ? "null" : value instanceof String ? JSONObject.quote((String)value) : value.toString(); }
    static boolean same(Object left, Object right) throws Exception {
        if (left == null || left == JSONObject.NULL) return right == null || right == JSONObject.NULL;
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject)left, b = (JSONObject)right;
            if (a.length() != b.length()) return false;
            for (Iterator<String> it = a.keys(); it.hasNext();) { String key = it.next(); if (!b.has(key) || !same(a.get(key), b.get(key))) return false; }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray)left, b = (JSONArray)right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left instanceof Number && right instanceof Number) return Double.compare(((Number)left).doubleValue(), ((Number)right).doubleValue()) == 0;
        return left.equals(right);
    }
    private String one(String table, String column, String where, String... args) {
        try (Cursor cursor = db().query(table, new String[]{column}, where, args, null, null, null, "1")) { return cursor.moveToFirst() ? cursor.getString(0) : null; }
    }
    private String meta(String key) { return one("meta", "json", "name=?", key); }
    private void meta(String key, String json) {
        ContentValues row = new ContentValues(); row.put("name", key); row.put("json", json); db().insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static String[] scope(String owner, String key, String workspace, String id) { return new String[]{owner,key,workspace,id}; }
    private static final String SCOPE = "owner=? AND item_key=? AND workspace=? AND table_id=?";
    private ContentValues scopeValues(String owner, String key, String workspace, String id) {
        ContentValues values = new ContentValues(); values.put("owner", owner); values.put("item_key", key); values.put("workspace", workspace); values.put("table_id", id); return values;
    }
    private void putJson(String table, ContentValues values, String json, String where, String... args) {
        // 保留未变化行，修改一格时只有该格与修订信息产生 SQL 写入。
        String old = one(table,"json",where,args);
        if (json.equals(old)) return;
        values.put("json",json); db().insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static boolean game(String game) { return "codeYuan".equals(game) || "ruYuan".equals(game); }
    private static void require(boolean ok, String message) throws IOException { if (!ok) throw new IOException(message); }
    private AtomicFile oldFile(String name) { return new AtomicFile(new File(files,name)); }
    private void deleteLegacy(String name) throws Exception {
        oldFile(name).delete();
        for(String suffix:new String[]{"",".bak",".new"})require(!new File(files,name+suffix).exists(),"旧数据清理失败，请重试："+name);
    }
    private String oldText(String name) throws Exception {
        AtomicFile old = oldFile(name);
        if (!old.getBaseFile().exists() && !new File(old.getBaseFile()+".bak").exists()) return null;
        byte[] bytes=old.readFully(); require(bytes.length<=64*1024*1024,"旧文件过大，未迁移"); return new String(bytes,StandardCharsets.UTF_8);
    }
    synchronized boolean rolesReady() { return "1".equals(meta("roles_ready")); }
    synchronized JSONObject migrationSource() throws Exception { return new JSONObject().put("ready",rolesReady()).put("text",rolesReady()?JSONObject.NULL:oldText("roles/state.json")); }
    private void validateRoles(JSONObject file) throws Exception {
        require(file.getInt("schemaVersion")==1 && file.getLong("revision")>=0,"角色数据格式无效");
        JSONArray roles=file.getJSONArray("roles"); JSONObject active=file.getJSONObject("active"), migrated=file.getJSONObject("migrated");
        require(roles.length()<=100,"角色数量超限"); Set<String> ids=new HashSet<>();
        for(int i=0;i<roles.length();i++){JSONObject role=roles.getJSONObject(i);require(role.getString("id").matches("role-[a-z0-9-]+")&&ids.add(role.getString("id"))&&game(role.getString("game")),"角色标识无效");require(!role.getString("name").trim().isEmpty(),"角色名称无效");role.getString("createdAt");role.getJSONObject("data");}
        for(String g:GAMES){migrated.getBoolean(g);String id=active.isNull(g)?null:active.getString(g);boolean exists=false,hasRoles=false;for(int i=0;i<roles.length();i++){JSONObject role=roles.getJSONObject(i);if(g.equals(role.getString("game"))){hasRoles=true;if(role.getString("id").equals(id))exists=true;}}require(id==null?!hasRoles:exists,"角色引用无效");}
        JSONObject guest=file.optJSONObject("guest");if(file.has("guest")){require(guest!=null,"草稿格式无效");for(Iterator<String> it=guest.keys();it.hasNext();){String g=it.next();require(game(g),"草稿游戏无效");guest.getJSONObject(g);}}
    }
    synchronized void migrateRoles(String text) throws Exception {
        if(rolesReady())return;
        JSONObject file=new JSONObject(text);validateRoles(file);
        transaction(()->{replaceRolesInner(file);require(same(file,readRolesInner()),"迁移读回核对失败，原数据已保留");meta("roles_ready","1");return null;});
        // 提交标记与记录同一事务；删除中断时下次只重试清理，绝不重导旧数据。
        cleanupRoles();
    }
    synchronized void cleanupRoles() throws Exception {
        require(rolesReady(),"数据库迁移未完成");deleteLegacy("roles/state.json");
    }
    private void requireRoles() throws Exception { require(rolesReady(),"角色数据库尚未迁移，请重新启动应用"); }
    private JSONObject roleHeader() throws Exception { return new JSONObject(meta("role_header")); }
    private void bumpRoles() throws Exception { JSONObject header=roleHeader();header.put("revision",header.getLong("revision")+1);meta("role_header",header.toString()); }
    private String owner(String g,String id) throws Exception {
        requireRoles();require(game(g),"游戏无效");Object active=roleHeader().getJSONObject("active").get(g);
        require(id.isEmpty()?active==JSONObject.NULL:id.equals(active),"当前角色已切换，请重新打开页面");
        return id.isEmpty()?"@guest:"+g:id;
    }
    private void replaceRolesInner(JSONObject file) throws Exception {
        for(String table:new String[]{"parts","states","documents","libraries","items","roles"})db().delete(table,null,null);
        meta("role_header",without(file,"roles","guest").toString());
        if(file.has("guest")){JSONObject guests=file.getJSONObject("guest");JSONArray games=new JSONArray();for(Iterator<String> it=guests.keys();it.hasNext();){String g=it.next();games.put(g);writeDataObject("@guest:"+g,guests.getJSONObject(g));}meta("guest_games",games.toString());}
        else db().delete("meta","name=?",new String[]{"guest_games"});
        JSONArray roles=file.getJSONArray("roles");for(int i=0;i<roles.length();i++){JSONObject role=roles.getJSONObject(i);ContentValues row=new ContentValues();row.put("owner",role.getString("id"));row.put("position",i);row.put("json",without(role,"data").toString());db().insertOrThrow("roles",null,row);writeDataObject(role.getString("id"),role.getJSONObject("data"));}
    }
    private void writeDataObject(String owner,JSONObject data) throws Exception {for(Iterator<String> it=data.keys();it.hasNext();){String key=it.next();writeItem(owner,key,data.get(key));}}
    private JSONObject readDataObject(String owner) throws Exception {
        JSONObject data=new JSONObject();try(Cursor rows=db().query("items",new String[]{"item_key"},"owner=?",new String[]{owner},null,null,null)){while(rows.moveToNext()){String key=rows.getString(0);data.put(key,readItem(owner,key));}}return data;
    }
    private JSONObject readRolesInner() throws Exception {
        JSONObject file=roleHeader();JSONArray roles=new JSONArray();
        try(Cursor rows=db().query("roles",new String[]{"owner","json"},null,null,null,null,"position")){while(rows.moveToNext())roles.put(new JSONObject(rows.getString(1)).put("data",readDataObject(rows.getString(0))));}file.put("roles",roles);
        String raw=meta("guest_games");if(raw!=null){JSONObject guest=new JSONObject();JSONArray games=new JSONArray(raw);for(int i=0;i<games.length();i++){String g=games.getString(i);guest.put(g,readDataObject("@guest:"+g));}file.put("guest",guest);}return file;
    }
    synchronized String readRoles() throws Exception {requireRoles();return readRolesInner().toString();}
    synchronized void writeRoles(long revision,String text) throws Exception {
        JSONObject next=new JSONObject(text);validateRoles(next);
        transaction(()->{requireRoles();require(roleHeader().getLong("revision")==revision&&next.getLong("revision")==revision+1,"角色数据已变化，请刷新后重试");replaceRolesInner(next);return null;});
    }
    synchronized JSONObject active(String g) throws Exception {
        requireRoles();require(game(g),"游戏无效");JSONObject header=roleHeader();Object id=header.getJSONObject("active").get(g);if(id==JSONObject.NULL)return null;
        String raw=one("roles","json","owner=?",id.toString());require(raw!=null,"角色不存在");return new JSONObject(raw).put("data",new JSONObject());
    }
    synchronized Object readData(String g,String id,String key) throws Exception {return readItem(owner(g,id),key);}
    synchronized void writeData(String g,String id,String key,String text) throws Exception {
        Object value=parse(text);transaction(()->{String owner=owner(g,id);writeItem(owner,key,value);if(id.isEmpty())ensureGuest(g);bumpRoles();return null;});
    }
    private void ensureGuest(String g) throws Exception {JSONArray games=new JSONArray(meta("guest_games")==null?"[]":meta("guest_games"));for(int i=0;i<games.length();i++)if(g.equals(games.getString(i)))return;games.put(g);meta("guest_games",games.toString());}
    private boolean libraryValue(String key,Object value) {
        if(!key.matches("yuan:v1:(team|recorder)-tables:(codeYuan|ruYuan)")||!(value instanceof JSONObject))return false;
        JSONObject data=((JSONObject)value).optJSONObject("data");return data!=null&&data.optJSONObject("draft")!=null&&data.optJSONArray("records")!=null&&data.optJSONArray("edits")!=null;
    }
    private void clearItem(String owner,String key) {for(String table:new String[]{"parts","states","documents","libraries","items"})db().delete(table,"owner=? AND item_key=?",new String[]{owner,key});}
    private void writeItem(String owner,String key,Object value) throws Exception {
        // 兼容未知模块与未来扩展字段；只对识别出的配队/操作表结构拆分。
        boolean library=libraryValue(key,value);clearItem(owner,key);
        ContentValues item=new ContentValues();item.put("owner",owner);item.put("item_key",key);item.put("is_library",library?1:0);
        item.put("json",library?without((JSONObject)value,"data").toString():encode(value));db().insertOrThrow("items",null,item);
        if(!library)return;JSONObject data=((JSONObject)value).getJSONObject("data");ContentValues row=new ContentValues();row.put("owner",owner);row.put("item_key",key);row.put("json",without(data,"records","edits","draft").toString());db().insertOrThrow("libraries",null,row);
        writeState(owner,key,"draft","",data.getJSONObject("draft"));
        for(String space:new String[]{"records","edits"}){JSONArray docs=data.getJSONArray(space);for(int i=0;i<docs.length();i++){JSONObject doc=docs.getJSONObject(i);String id=doc.getString("id");ContentValues document=scopeValues(owner,key,space,id);document.put("position",i);document.put("json",without(doc,"state").toString());db().insertOrThrow("documents",null,document);writeState(owner,key,space,id,doc.getJSONObject("state"));}}
    }
    private Object readItem(String owner,String key) throws Exception {
        String raw=one("items","json","owner=? AND item_key=?",owner,key);if(raw==null)return null;
        if(!"1".equals(one("items","is_library","owner=? AND item_key=?",owner,key)))return parse(raw);
        JSONObject data=new JSONObject(one("libraries","json","owner=? AND item_key=?",owner,key));data.put("draft",readState(owner,key,"draft",""));
        for(String space:new String[]{"records","edits"}){JSONArray docs=new JSONArray();try(Cursor rows=db().query("documents",new String[]{"table_id","json"},"owner=? AND item_key=? AND workspace=?",new String[]{owner,key,space},null,null,"position")){while(rows.moveToNext())docs.put(new JSONObject(rows.getString(1)).put("state",readState(owner,key,space,rows.getString(0))));}data.put(space,docs);}
        return new JSONObject(raw).put("data",data);
    }
    private static final class Part {final String kind,id,json;final int position;Part(String kind,String id,int position,Object value){this.kind=kind;this.id=id;this.position=position;json=encode(value);}}
    private void writeState(String owner,String key,String space,String id,JSONObject state) throws Exception {
        JSONObject header=without(state,"slots","presets","rounds");List<Part> parts=new ArrayList<>();
        if(state.has("slots")){JSONArray slots=state.getJSONArray("slots");for(int i=0;i<slots.length();i++)parts.add(new Part("slot",String.valueOf(i),i,slots.get(i)));}
        if(state.has("presets")){header.put("presets",new JSONObject());JSONObject presets=state.getJSONObject("presets");int i=0;for(Iterator<String> it=presets.keys();it.hasNext();){String p=it.next();parts.add(new Part("preset",p,i++,presets.get(p)));}}
        if(state.has("rounds")){header.put("rounds",new JSONArray());JSONArray rounds=state.getJSONArray("rounds");for(int i=0;i<rounds.length();i++){JSONObject round=rounds.getJSONObject(i);String r=String.valueOf(round.getLong("id"));parts.add(new Part("round",r,i,without(round,"cells")));JSONArray cells=round.getJSONArray("cells");for(int j=0;j<cells.length();j++)parts.add(new Part("cell",r+":"+j,j,cells.get(j)));}}
        putJson("states",scopeValues(owner,key,space,id),header.toString(),SCOPE,scope(owner,key,space,id));
        Map<String,String> old=new HashMap<>();Map<String,Integer> positions=new HashMap<>();
        try(Cursor rows=db().query("parts",new String[]{"kind","part_id","json","position"},SCOPE,scope(owner,key,space,id),null,null,null)){while(rows.moveToNext()){String p=rows.getString(0)+"\n"+rows.getString(1);old.put(p,rows.getString(2));positions.put(p,rows.getInt(3));}}
        for(Part part:parts){String p=part.kind+"\n"+part.id;String before=old.remove(p);if(part.json.equals(before)&&positions.get(p)==part.position)continue;ContentValues row=scopeValues(owner,key,space,id);row.put("kind",part.kind);row.put("part_id",part.id);row.put("position",part.position);row.put("json",part.json);db().insertWithOnConflict("parts",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
        for(String p:old.keySet()){int split=p.indexOf('\n');db().delete("parts",SCOPE+" AND kind=? AND part_id=?",new String[]{owner,key,space,id,p.substring(0,split),p.substring(split+1)});}
    }
    private JSONObject readState(String owner,String key,String space,String id) throws Exception {
        String raw=one("states","json",SCOPE,scope(owner,key,space,id));if(raw==null)return null;JSONObject state=new JSONObject(raw);JSONArray slots=new JSONArray();Map<String,JSONObject> rounds=new LinkedHashMap<>();List<String[]> cells=new ArrayList<>();
        try(Cursor rows=db().query("parts",new String[]{"kind","part_id","json"},SCOPE,scope(owner,key,space,id),null,null,"position")){while(rows.moveToNext()){String kind=rows.getString(0),part=rows.getString(1);Object value=parse(rows.getString(2));switch(kind){case "slot":slots.put(Integer.parseInt(part),value);break;case "preset":state.getJSONObject("presets").put(part,value);break;case "round":rounds.put(part,((JSONObject)value).put("cells",new JSONArray()));break;case "cell":cells.add(new String[]{part,rows.getString(2)});break;}}}
        state.put("slots",slots);for(String[] cell:cells){int split=cell[0].lastIndexOf(':');JSONObject round=rounds.get(cell[0].substring(0,split));require(round!=null,"操作回合数据损坏");round.getJSONArray("cells").put(Integer.parseInt(cell[0].substring(split+1)),parse(cell[1]));}
        if(state.has("rounds")){JSONArray list=new JSONArray();for(JSONObject round:rounds.values())list.put(round);state.put("rounds",list);}return state;
    }
    synchronized JSONObject workspace(String g,String id,String key,String tableId) throws Exception {
        String owner=owner(g,id);if(one("libraries","json","owner=? AND item_key=?",owner,key)==null)return null;
        JSONObject state=tableId.isEmpty()?readState(owner,key,"draft",""):readState(owner,key,"edits",tableId);if(state==null)state=readState(owner,key,"records",tableId);require(state!=null,"表格已删除或不存在，请返回列表");return state;
    }
    private String workspaceHeader(String owner,String key,String tableId) {
        String raw=one("states","json",SCOPE,scope(owner,key,tableId.isEmpty()?"draft":"edits",tableId));
        return raw==null&&!tableId.isEmpty()?one("states","json",SCOPE,scope(owner,key,"records",tableId)):raw;
    }
    synchronized Long workspaceRevision(String g,String id,String key,String tableId) throws Exception {
        String raw=workspaceHeader(owner(g,id),key,tableId);return raw==null?null:new JSONObject(raw).getLong("revision");
    }
    private void touchLibrary(String owner,String key) throws Exception {
        JSONObject library=new JSONObject(one("libraries","json","owner=? AND item_key=?",owner,key));library.put("revision",library.getLong("revision")+1);
        ContentValues row=new ContentValues();row.put("json",library.toString());db().update("libraries",row,"owner=? AND item_key=?",new String[]{owner,key});
        JSONObject envelope=new JSONObject(one("items","json","owner=? AND item_key=?",owner,key));
        java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);date.setTimeZone(TimeZone.getTimeZone("UTC"));
        envelope.put("updatedAt",date.format(new Date()));row.put("json",envelope.toString());db().update("items",row,"owner=? AND item_key=?",new String[]{owner,key});bumpRoles();
    }
    synchronized void patchWorkspace(String g,String id,String key,String tableId,String text) throws Exception {
        JSONObject patch=new JSONObject(text),header=patch.getJSONObject("header");
        transaction(()->{
            String owner=owner(g,id),previous=workspaceHeader(owner,key,tableId),space=tableId.isEmpty()?"draft":"edits";
            require(previous!=null&&header.getLong("revision")==new JSONObject(previous).getLong("revision")+1,"草稿已变化，请重新打开页面");
            if(!tableId.isEmpty()&&one("documents","json",SCOPE,scope(owner,key,space,tableId))==null){
                JSONObject saved=new JSONObject(one("documents","json",SCOPE,scope(owner,key,"records",tableId)));
                ContentValues row=scopeValues(owner,key,space,tableId);row.put("position",Integer.MAX_VALUE);row.put("json",new JSONObject().put("id",tableId).put("baseRevision",saved.getLong("revision")).toString());db().insertOrThrow("documents",null,row);
                // 第一次编辑通过 SQL 复制已保存版本，之后只更新变化行，无正文跨桥往返。
                db().execSQL("INSERT INTO parts(owner,item_key,workspace,table_id,kind,part_id,position,json) SELECT owner,item_key,?,table_id,kind,part_id,position,json FROM parts WHERE "+SCOPE,new Object[]{space,owner,key,"records",tableId});
            }
            JSONArray removed=patch.getJSONArray("removed"),changes=patch.getJSONArray("changes");
            for(int i=0;i<removed.length();i++){JSONObject part=removed.getJSONObject(i);db().delete("parts",SCOPE+" AND kind=? AND part_id=?",new String[]{owner,key,space,tableId,part.getString("kind"),part.getString("id")});}
            for(int i=0;i<changes.length();i++){
                JSONObject part=changes.getJSONObject(i);String kind=part.getString("kind"),partId=part.getString("id");int position=part.getInt("position");
                require(Arrays.asList("slot","preset","round","cell").contains(kind)&&position>=0&&!partId.isEmpty(),"表格更新格式无效");
                ContentValues row=scopeValues(owner,key,space,tableId);row.put("kind",kind);row.put("part_id",partId);row.put("position",position);row.put("json",encode(part.get("value")));db().insertWithOnConflict("parts",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            }
            putJson("states",scopeValues(owner,key,space,tableId),header.toString(),SCOPE,scope(owner,key,space,tableId));touchLibrary(owner,key);return null;
        });
    }
    synchronized void saveWorkspace(String g,String id,String key,String tableId,String text) throws Exception {
        JSONObject next=new JSONObject(text);transaction(()->{String owner=owner(g,id);
            // 写前仅核对状态头版本，不为版本检查重新组装全部回合与单元格。
            String previous=one("states","json",SCOPE,scope(owner,key,tableId.isEmpty()?"draft":"edits",tableId));
            if(previous==null&&!tableId.isEmpty())previous=one("states","json",SCOPE,scope(owner,key,"records",tableId));
            require(previous!=null&&next.getLong("revision")==new JSONObject(previous).getLong("revision")+1,"草稿已变化，请重新打开页面");
            String space=tableId.isEmpty()?"draft":"edits";
            if(!tableId.isEmpty()&&one("documents","json",SCOPE,scope(owner,key,space,tableId))==null){String saved=one("documents","json",SCOPE,scope(owner,key,"records",tableId));require(saved!=null,"表格已删除");JSONObject doc=new JSONObject().put("id",tableId).put("baseRevision",new JSONObject(saved).getLong("revision"));ContentValues row=scopeValues(owner,key,space,tableId);row.put("position",Integer.MAX_VALUE);row.put("json",doc.toString());db().insertOrThrow("documents",null,row);}
            writeState(owner,key,space,tableId,next);JSONObject library=new JSONObject(one("libraries","json","owner=? AND item_key=?",owner,key));library.put("revision",library.getLong("revision")+1);ContentValues row=new ContentValues();row.put("json",library.toString());db().update("libraries",row,"owner=? AND item_key=?",new String[]{owner,key});bumpRoles();return null;});
    }
    synchronized JSONObject table(String g,String id,String key,String tableId) throws Exception {String owner=owner(g,id);String raw=one("documents","json",SCOPE,scope(owner,key,"records",tableId));require(raw!=null,"表格已删除或不存在，请返回列表");return new JSONObject(raw).put("state",readState(owner,key,"records",tableId));}
    synchronized JSONObject tableList(String g,String id,String key) throws Exception {
        String owner=owner(g,id),raw=one("libraries","json","owner=? AND item_key=?",owner,key);if(raw==null)return null;JSONObject header=new JSONObject(raw);JSONArray records=new JSONArray();
        try(Cursor rows=db().query("documents",new String[]{"table_id","json"},"owner=? AND item_key=? AND workspace=?",new String[]{owner,key,"records"},null,null,"position")){
            while(rows.moveToNext()){
                String tableId=rows.getString(0);JSONObject state=new JSONObject(one("states","json",SCOPE,scope(owner,key,"records",tableId)));int members=0,rounds=0;
                // 只按索引统计摘要，不将正文和所有单元格跨桥传到列表页。
                try(Cursor counts=db().rawQuery("SELECT kind,COUNT(*) FROM parts WHERE "+SCOPE+" AND ((kind='slot' AND json!='null') OR kind='round') GROUP BY kind",scope(owner,key,"records",tableId))){while(counts.moveToNext())if("slot".equals(counts.getString(0)))members=counts.getInt(1);else rounds=counts.getInt(1);}
                JSONObject summary=new JSONObject().put("members",members).put("rounds",rounds).put("hasNote",!state.optString("note").isEmpty()).put("stateRevision",state.getLong("revision"));records.put(new JSONObject(rows.getString(1)).put("summary",summary));
            }
        }return header.put("records",records);
    }
    synchronized JSONObject saveTable(String g,String roleId,String key,String requestText) throws Exception {
        JSONObject request=new JSONObject(requestText);String id=request.getString("id"),name=request.getString("name");
        require(!name.isEmpty()&&name.equals(name.trim())&&!name.contains("\n")&&!name.contains("\r")&&name.codePointCount(0,name.length())<=40,"表名无效");
        return transaction(()->{
            String owner=owner(g,roleId);JSONObject state=workspace(g,roleId,key,id);
            require(state!=null&&state.getLong("revision")==request.getLong("revision"),"草稿已变化，请重新打开后保存");
            JSONObject library=new JSONObject(one("libraries","json","owner=? AND item_key=?",owner,key));
            try(Cursor rows=db().query("documents",new String[]{"table_id","json"},"owner=? AND item_key=? AND workspace='records'",new String[]{owner,key},null,null,null)){
                while(rows.moveToNext())require(id.equals(rows.getString(0))||!name.equals(new JSONObject(rows.getString(1)).getString("name")),"已有同名表，请换一个名称");
            }
            JSONObject saved;String target=id;int position=0;
            if(id.isEmpty()){
                long next=library.getLong("nextId");target="table-"+next;library.put("nextId",next+1);
                // 新保存的草稿沿用原列表前插规则；手动排序也保持一致。
                db().execSQL("UPDATE documents SET position=position+1 WHERE owner=? AND item_key=? AND workspace='records'",new Object[]{owner,key});
                saved=new JSONObject().put("id",target).put("name",name).put("revision",0).put("createdAt",request.getString("time"));
                JSONObject empty=request.getJSONObject("empty");empty.put("revision",state.getLong("revision")+1);writeState(owner,key,"draft","",empty);
            }else{
                String raw=one("documents","json",SCOPE,scope(owner,key,"records",id));require(raw!=null,"表格已删除");saved=new JSONObject(raw);
                require(saved.getLong("revision")==request.getLong("savedRevision"),"本地表已变化，请返回列表重新编辑");
                String edit=one("documents","json",SCOPE,scope(owner,key,"edits",id));require(edit==null||new JSONObject(edit).getLong("baseRevision")==saved.getLong("revision"),"本地表已变化，请返回列表重新编辑");
                position=Integer.parseInt(one("documents","position",SCOPE,scope(owner,key,"records",id)));
                saved.put("name",name).put("revision",saved.getLong("revision")+1);
                for(String table:new String[]{"parts","states","documents"})db().delete(table,SCOPE,scope(owner,key,"edits",id));
            }
            saved.put("updatedAt",request.getString("time"));ContentValues document=scopeValues(owner,key,"records",target);document.put("position",position);document.put("json",saved.toString());db().insertWithOnConflict("documents",null,document,SQLiteDatabase.CONFLICT_REPLACE);
            writeState(owner,key,"records",target,state);library.put("revision",library.getLong("revision")+1);ContentValues row=new ContentValues();row.put("json",library.toString());db().update("libraries",row,"owner=? AND item_key=?",new String[]{owner,key});bumpRoles();
            return saved.put("state",state);
        });
    }
    // 图鉴和角色共用数据库事务，但图片继续使用不可变文件，避免图片跨桥复制。
    private JSONObject readCatalogInner() throws Exception {
        String raw=meta("catalog_header");if(raw==null)return null;JSONObject file=new JSONObject(raw),games=new JSONObject();
        try(Cursor rows=db().query("catalog_games",new String[]{"game","json"},null,null,null,null,null)){while(rows.moveToNext()){String game=rows.getString(0);JSONObject data=new JSONObject(rows.getString(1)),agents=new JSONObject();try(Cursor values=db().query("agents",new String[]{"agent_id","json"},"game=?",new String[]{game},null,null,"position")){while(values.moveToNext())agents.put(values.getString(0),new JSONObject(values.getString(1)));}games.put(game,data.put("agents",agents));}}return file.put("games",games);
    }
    private void replaceCatalogInner(JSONObject file) throws Exception {
        db().delete("agents",null,null);db().delete("catalog_games",null,null);db().delete("meta","name=?",new String[]{"catalog_header"});if(file==null)return;
        require(file.getInt("schemaVersion")==1&&file.getLong("revision")>=0,"图鉴格式无效");JSONObject games=file.getJSONObject("games");meta("catalog_header",without(file,"games").toString());
        for(Iterator<String> it=games.keys();it.hasNext();){String game=it.next();JSONObject data=games.getJSONObject(game);ContentValues row=new ContentValues();row.put("game",game);row.put("json",without(data,"agents").toString());db().insertOrThrow("catalog_games",null,row);JSONObject agents=data.getJSONObject("agents");int i=0;for(Iterator<String> ids=agents.keys();ids.hasNext();){String id=ids.next();ContentValues agent=new ContentValues();agent.put("game",game);agent.put("agent_id",id);agent.put("position",i++);agent.put("json",agents.getJSONObject(id).toString());db().insertOrThrow("agents",null,agent);}}
    }
    synchronized JSONObject catalogMigrationSource() throws Exception {boolean ready="1".equals(meta("catalog_ready"));return new JSONObject().put("ready",ready).put("text",ready?JSONObject.NULL:oldText("catalog/state.json"));}
    synchronized void migrateCatalog(String text,String cacheBackup) throws Exception {
        if(!"1".equals(meta("catalog_ready"))){String nativeBackup=oldText("catalog/before-shared.json"),backup=nativeBackup==null?cacheBackup:nativeBackup;JSONObject value=text==null||text.isEmpty()?null:new JSONObject(text);
            transaction(()->{replaceCatalogInner(value);require(same(value,readCatalogInner()),"图鉴迁移核对失败");if(backup!=null&&!backup.isEmpty())meta("catalog_before_shared",backup);meta("catalog_ready","1");return null;});}
        deleteLegacy("catalog/state.json");deleteLegacy("catalog/before-shared.json");
    }
    synchronized void ensureCatalog() throws Exception {migrateCatalog("1".equals(meta("catalog_ready"))?null:oldText("catalog/state.json"),null);}
    synchronized String readCatalog() throws Exception {ensureCatalog();JSONObject file=readCatalogInner();return file==null?null:file.toString();}
    synchronized void writeCatalog(long revision,String text) throws Exception {
        ensureCatalog();JSONObject next=new JSONObject(text);transaction(()->{JSONObject old=readCatalogInner();require((old==null?0:old.getLong("revision"))==revision&&next.getLong("revision")==revision+1,"图鉴已变化，请重新打开页面");if(old!=null&&!old.optBoolean("shared")&&next.optBoolean("shared")&&meta("catalog_before_shared")==null)meta("catalog_before_shared",old.toString());replaceCatalogInner(next);return null;});
    }
    synchronized void restore(JSONObject roles,JSONObject catalog) throws Exception {
        validateRoles(roles);transaction(()->{replaceRolesInner(roles);replaceCatalogInner(catalog);require(same(roles,readRolesInner())&&same(catalog,readCatalogInner()),"备份恢复核对失败");meta("roles_ready","1");meta("catalog_ready","1");return null;});cleanupRoles();ensureCatalog();
    }
    synchronized void prepareLegacyRecovery() throws Exception {
        // 旧版恢复日志可能跨升级存在；等待 Web 设置一并还原后重新导入恢复源。
        // 只清标记，现有数据库保留至下一次迁移事务成功，避免旧日志被直接忽略。
        transaction(()->{db().delete("meta","name IN (?,?)",new String[]{"roles_ready","catalog_ready"});return null;});
    }
    // 仅供独立原生测试关闭连接，模拟冷启动；正式运行不删除数据库。
    synchronized void closeForTest(){helper.close();synchronized(AppDatabase.class){INSTANCES.values().remove(this);}}
}
