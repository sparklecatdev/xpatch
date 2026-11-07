package dev.sparklecat.xpatch.runtime;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ContentFilterTest {
    public static class Post {
        final String id, text; final Object promoted;
        Post(String id, String text, Object promoted) { this.id=id; this.text=text; this.promoted=promoted; }
        public String getId() { return id; }
        public String getText() { return text; }
    }
    public static class Item { final Post item; Item(Post item) { this.item=item; } }
    public static class Conversation { final List<String> ids; public Conversation(List<String> ids) { this.ids=ids; } }
    public static class Module {
        final List<Item> items; final Object header, footer, client; final Conversation display; final long sort; final String id;
        public Module(List<Item> items, Object header, Object footer, Conversation display, long sort, String id, Object client) { this.items=items; this.header=header; this.footer=footer; this.display=display; this.sort=sort; this.id=id; this.client=client; }
    }
    public static class Trend { final Object metadata; Trend(Object metadata) { this.metadata=metadata; } }
    public static class TrendEntry { final Trend trend; TrendEntry(Trend trend) { this.trend=trend; } }
    public static class Rtb { }
    enum Tab { HOME, GROK, NOTIFICATIONS, DM, SPACES }
    ContentFilter filter() throws Exception {
        JSONObject models = new JSONObject()
            .put(Post.class.getName(), new JSONObject().put("$kind","post").put("promotedMetadata","promoted"))
            .put(Item.class.getName(), new JSONObject().put("$kind","item").put("item","item"))
            .put(Conversation.class.getName(), new JSONObject().put("$kind","conversation").put("allTweetIds","ids"))
            .put(Module.class.getName(), new JSONObject().put("$kind","module").put("innerContent","items").put("moduleHeader","header").put("moduleFooter","footer").put("displayType","display").put("sortIndex","sort").put("entryId","id").put("clientEventInfo","client"))
            .put(Trend.class.getName(), new JSONObject().put("$kind","trendData").put("promotedMetadata","metadata"))
            .put(TrendEntry.class.getName(), new JSONObject().put("$kind","trend").put("timelineTrend","trend"))
            .put(Rtb.class.getName(), new JSONObject().put("$kind","ad"));
        return new ContentFilter(new JSONObject().put("modernModels", models).put("entryFields", new JSONObject().put(Post.class.getName(),"id").put(Module.class.getName(),"id")));
    }
    @Test public void removesNestedAdsAndRepairsConversationWithoutChangingMetadata() throws Exception {
        Post organic=new Post("tweet-1","hello",null), ad=new Post("tweet-2","sponsored",new Object());
        Object header=new Object(),footer=new Object(),client=new Object();
        Module original=new Module(Arrays.asList(new Item(organic),new Item(ad)),header,footer,new Conversation(Arrays.asList("tweet-1","tweet-2")),42L,"conversation-1",client);
        Module result=(Module)filter().filter(original,new JSONObject().put("ads",true));
        assertNotSame(original,result); assertEquals(2,original.items.size()); assertEquals(1,result.items.size()); assertSame(organic,result.items.get(0).item);
        assertEquals(Collections.singletonList("tweet-1"),result.display.ids); assertSame(header,result.header); assertSame(footer,result.footer); assertSame(client,result.client); assertEquals(42L,result.sort);
    }
    @Test public void removesEmptyModulesSponsoredTrendsAndRtbOnlyWhenEnabled() throws Exception {
        ContentFilter filter=filter(); JSONObject on=new JSONObject().put("ads",true),off=new JSONObject().put("ads",false);
        Module ads=new Module(Collections.singletonList(new Item(new Post("promoted-1","",null))),null,null,null,0,"module-1",null);
        for(Object value:Arrays.asList(ads,new TrendEntry(new Trend(new Object())),new Rtb())) { assertNull(filter.filter(value,on)); assertSame(value,filter.filter(value,off)); }
        TrendEntry organic=new TrendEntry(new Trend(null)); assertSame(organic,filter.filter(organic,on));
    }
    @Test public void appliesOptionalFiltersAndRetainsUnknownAndUnchangedObjects() throws Exception {
        ContentFilter filter=filter(); Object unknown=new Object(); assertSame(unknown,filter.filter(unknown,new JSONObject().put("ads",true)));
        List<Post> original=Collections.singletonList(new Post("tweet-1","ordinary post",null)); assertSame(original,filter.timeline(original,new JSONObject().put("ads",true)));
        assertNull(filter.filter(new Post("tweet-2","Spoiler alert",null),new JSONObject().put("mutedWords","news\nspoiler")));
        Post follow=new Post("who-to-follow-1","",null); assertSame(follow,filter.filter(follow,new JSONObject().put("ads",true))); assertNull(filter.filter(follow,new JSONObject().put("hideWhoToFollow",true)));
    }
    @Test public void filtersImmutableTimelineAndNeverMutatesOriginal() throws Exception {
        List<Object> original=Collections.unmodifiableList(Arrays.asList(new Post("tweet-1","",null),new Rtb()));
        List<?> result=(List<?>)filter().timeline(original,new JSONObject().put("ads",true)); assertEquals(1,result.size()); assertEquals(2,original.size());
    }
    @Test public void navigationPreservesHomeAndUnknownFutureTabsAndReorders() throws Exception {
        LinkedHashMap<Tab,String> original=new LinkedHashMap<>(); for(Tab tab:Tab.values())original.put(tab,tab.name());
        Map<?,?> result=ContentFilter.navigation(original,new JSONObject().put("navHidden","HOME,GROK").put("navOrder","DM,HOME,DM"));
        assertEquals(Arrays.asList(Tab.DM,Tab.HOME,Tab.NOTIFICATIONS,Tab.SPACES),new ArrayList<>(result.keySet())); assertEquals(5,original.size());
        assertSame(original,ContentFilter.navigation(original,new JSONObject()));
    }
    @Test public void rejectsInvalidLiveNavigationAndOversizeMuteLists() {
        ContentFilter.validateNavigation("GROK,SPACES","DM,HOME", "a phrase\nanother");
        for(String hidden:Arrays.asList("HOME","grok","UNKNOWN",String.join("",Collections.nCopies(513," ")))) {
            try { ContentFilter.validateNavigation(hidden,"",""); fail(hidden); } catch(IllegalArgumentException expected) { }
        }
        for(String words:Arrays.asList(String.join("\n",Collections.nCopies(65,"word")),String.join("",Collections.nCopies(4097,"x")))) {
            try { ContentFilter.validateNavigation("","",words); fail(); } catch(IllegalArgumentException expected) { }
        }
    }
}
