package com.local.yuanatlas;
import android.content.*;import android.net.Uri;import android.os.ParcelFileDescriptor;import java.io.*;
/** 仅进入测试 APK，且只提供固定样例，验证系统 content URI 导入链路。 */
public final class FixtureProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  if(!"r".equals(mode)||!"follow-texture-pixels.jpg".equals(uri.getLastPathSegment()))throw new FileNotFoundException();
  File file=new File(getContext().getCacheDir(),"follow-test.jpg");
  try(InputStream in=getContext().getAssets().open("follow-texture-pixels.jpg");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(IOException e){throw new FileNotFoundException(e.toString());}
  return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
 }
 @Override public String getType(Uri uri){return "image/jpeg";}
 @Override public android.database.Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
