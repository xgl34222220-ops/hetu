package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import org.json.*;
import java.util.*;

/** Opt-in bounded observations, never presented as complete traffic or REJECT logs. */
final class ProxyObservations extends SQLiteOpenHelper {
    private static final Object LOCK=new Object();
    static final int LIMIT=300;
    private final SharedPreferences prefs;
    ProxyObservations(Context context){super(context.getApplicationContext(),"proxy-observations.db",null,1);prefs=context.getSharedPreferences("hetu",0);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE seen (id TEXT PRIMARY KEY, body TEXT NOT NULL, last INTEGER NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported observations database version");}
    long epoch(){return prefs.getLong("proxyHistoryEpoch",0);}
    void setEnabled(boolean on){synchronized(LOCK){if(!prefs.edit().putBoolean("proxyHistory",on).putLong("proxyHistoryEpoch",epoch()+1).commit())throw new IllegalStateException("无法保存记录开关");}}
    void clear(){synchronized(LOCK){if(!prefs.edit().putLong("proxyHistoryEpoch",epoch()+1).remove("proxyHistoryError").commit())throw new IllegalStateException("无法清空记录状态");getWritableDatabase().delete("seen",null,null);}}
    void capture(JSONObject snapshot,long expectedEpoch)throws Exception {
        synchronized(LOCK){
            if(!prefs.getBoolean("proxyHistory",false)||epoch()!=expectedEpoch)return;
            JSONArray entries=snapshot.optJSONArray("connections");if(entries==null)return;
            SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();db.beginTransaction();
            try{
                // Save only explicit, non-credential connection metadata. Nothing inferred from foreground apps.
                for(int n=0;n<Math.min(entries.length(),2000);n++){
                    JSONObject e=entries.optJSONObject(n);if(e==null)continue;String id=e.optString("id","");JSONObject m=e.optJSONObject("metadata");if(id.isEmpty()||m==null)continue;
                    JSONObject copy=new JSONObject().put("id",id).put("host",bounded(m.optString("host"))).put("ip",bounded(m.optString("destinationIP"))).put("port",bounded(m.optString("destinationPort"))).put("network",bounded(m.optString("network"))).put("rule",bounded(e.optString("rule"))).put("chains",bounded(e.optString("chains"))).put("upload",Math.max(0,e.optLong("upload"))).put("download",Math.max(0,e.optLong("download"))).put("lastSeen",now);
                    ContentValues v=new ContentValues();v.put("id",id);v.put("body",copy.toString());v.put("last",now);db.insertWithOnConflict("seen",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.execSQL("DELETE FROM seen WHERE id NOT IN (SELECT id FROM seen ORDER BY last DESC, id LIMIT "+LIMIT+")");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    List<JSONObject> readRecent()throws Exception {synchronized(LOCK){ArrayList<JSONObject> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM seen ORDER BY last DESC, id LIMIT "+LIMIT,null)){while(c.moveToNext())out.add(new JSONObject(c.getString(0)));}return out;}}
    private static String bounded(String v){return v.length()>512?v.substring(0,512):v;}
}
