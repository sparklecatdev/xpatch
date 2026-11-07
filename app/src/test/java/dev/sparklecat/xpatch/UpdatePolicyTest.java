package dev.sparklecat.xpatch;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    JSONObject release(String version) throws Exception {
        String tag="v"+version,root="https://github.com/"+UpdatePolicy.REPO+"/releases/download/"+tag+"/";
        return new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",version.contains("-")).put("body","Release notes")
            .put("assets",new JSONArray().put(new JSONObject().put("name","xpatch.apk").put("state","uploaded").put("size",10).put("browser_download_url",root+"xpatch.apk"))
            .put(new JSONObject().put("name","SHA256SUMS").put("state","uploaded").put("browser_download_url",root+"SHA256SUMS")));
    }
    @Test public void semanticOrderingAndMalformedVersions() {
        String[] ordered={"0.3.0-alpha.2","0.3.0-alpha.10","0.3.0-beta","0.3.0-rc.1","0.3.0","0.10.0","1.0.0"};
        for(int i=1;i<ordered.length;i++)assertTrue(ReleaseVersion.parse(ordered[i]).compareTo(ReleaseVersion.parse(ordered[i-1]))>0);
        for(String bad:Arrays.asList("1","1.2","01.2.3","1.2.3-alpha.01","1.2.3+bad","1.2.3/evil","999999999999999999999999.2.3"))assertNull(bad,ReleaseVersion.parse(bad));
        assertEquals(0,ReleaseVersion.parse("v1.2.3").compareTo(ReleaseVersion.parse("1.2.3")));
    }
    @Test public void selectsNewestUsableReleaseAndRespectsPreviewSetting() throws Exception {
        JSONArray list=new JSONArray().put(release("0.4.0-alpha.1")).put(release("0.3.0")).put(release("0.2.0"));
        assertEquals("v0.4.0-alpha.1",UpdatePolicy.select(list.toString(),"0.2.0",true).tag);
        assertEquals("v0.3.0",UpdatePolicy.select(list.toString(),"0.2.0",false).tag);
        assertNull(UpdatePolicy.select(list.toString(),"1.0.0",true));
        JSONObject draft=release("5.0.0").put("draft",true); assertNull(UpdatePolicy.select(new JSONArray().put(draft).toString(),"0.2.0",true));
    }
    @Test public void rejectsIncompleteAmbiguousOversizeAndForeignAssets() throws Exception {
        for(int mode=0;mode<5;mode++) {
            JSONObject row=release("1.0.0"); JSONArray assets=row.getJSONArray("assets");
            if(mode==0)assets.remove(1);
            if(mode==1)assets.put(new JSONObject(assets.getJSONObject(0).toString()));
            if(mode==2)assets.getJSONObject(0).put("size",UpdatePolicy.MAX_APK+1);
            if(mode==3)assets.getJSONObject(0).put("browser_download_url","https://github.com/other/repo/releases/download/v1.0.0/xpatch.apk");
            if(mode==4)assets.getJSONObject(1).put("state","new");
            assertNull("mode "+mode,UpdatePolicy.select(new JSONArray().put(row).toString(),"0.2.0",true));
        }
        for(String url:Arrays.asList("http://github.com/a","https://github.com.evil.test/a","https://user@github.com/a","https://github.com:444/a"))assertFalse(url,UpdatePolicy.downloadUrlAllowed(URI.create(url)));
    }
    @Test public void requiresExactChecksumSizePackageAndIncreasingVersion() throws Exception {
        File apk=temp.newFile(); Files.write(apk.toPath(),new byte[]{1,2,3}); String hash=ApkPatcher.sha256(apk);
        assertEquals(hash,UpdatePolicy.checksum(hash+"  xpatch.apk\n","xpatch.apk")); UpdateTrust.checkDownload(apk,3,hash);
        for(String sums:Arrays.asList(hash+"  other.apk",hash+"  xpatch.apk\n"+hash+" *xpatch.apk"))try{UpdatePolicy.checksum(sums,"xpatch.apk");fail();}catch(IOException expected){}
        try{UpdateTrust.checkDownload(apk,4,hash);fail();}catch(IOException expected){}
        try{UpdateTrust.checkDownload(apk,3,String.join("",Collections.nCopies(64,"0")));fail();}catch(IOException expected){}
        try{UpdateTrust.checkPackage("other.app","dev.sparklecat.xpatch");fail();}catch(IOException expected){}
        UpdatePolicy.Release release=UpdatePolicy.select(new JSONArray().put(release("0.3.0")).toString(),"0.2.0",true);
        UpdatePolicy.checkVersion("0.3.0",4,"0.2.0",3,release);
        for(String version:Arrays.asList("0.2.0","0.4.0"))try{UpdatePolicy.checkVersion(version,4,"0.2.0",3,release);fail();}catch(IOException expected){}
        try{UpdatePolicy.checkVersion("0.3.0",3,"0.2.0",3,release);fail();}catch(IOException expected){}
    }
    @Test public void paginatesReleaseFeedAndBoundsRequests() throws Exception {
        List<String> urls=new ArrayList<>();
        String feed=ReleaseFeed.read((url,limit)->{
            urls.add(url); assertEquals(2*1024*1024,limit);
            JSONArray rows=new JSONArray();
            for(int i=0;i<(urls.size()==1?UpdatePolicy.RELEASE_PAGE_SIZE:1);i++)rows.put(release("0.3.0"));
            return rows.toString();
        });
        assertEquals(UpdatePolicy.RELEASE_PAGE_SIZE+1,new JSONArray(feed).length());
        assertTrue(urls.get(1).endsWith("&page=2")); urls.clear();
        ReleaseFeed.read((url,limit)->{
            urls.add(url); JSONArray rows=new JSONArray();
            for(int i=0;i<UpdatePolicy.RELEASE_PAGE_SIZE;i++)rows.put(new JSONObject());
            return rows.toString();
        });
        assertEquals(UpdatePolicy.MAX_RELEASES/UpdatePolicy.RELEASE_PAGE_SIZE,urls.size());
        try { ReleaseFeed.read((url,limit)->{ JSONArray rows=new JSONArray(); for(int i=0;i<=UpdatePolicy.RELEASE_PAGE_SIZE;i++)rows.put(new JSONObject()); return rows.toString(); }); fail(); }
        catch(IOException expected) { }
        try { ReleaseFeed.read((url,limit)->{ throw new IOException("offline"); }); fail(); }
        catch(IOException expected) { assertEquals("offline",expected.getMessage()); }
    }
}
