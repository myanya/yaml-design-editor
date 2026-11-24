package verification;

import example.todos.TodoApplication;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 実際の HTTP と H2 で、全画面と更新・拒否・値の往復を検証する。 */
@SpringBootTest(classes=TodoApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:business-web-verification;DB_CLOSE_DELAY=-1"
})
class BusinessWebIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    static String enc(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
    static String decode(String text){return text.replace("&quot;","\"").replace("&#39;","'").replace("&#x27;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");}
    HttpResponse<String> request(String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(body==null)builder.GET();else builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String get(String path) throws Exception {var r=request(path,null);assertEquals(200,r.statusCode(),r.body());assertFalse(r.body().contains("th:replace"));return r.body();}
    String post(String path,String body) throws Exception {var r=request(path,body);assertEquals(200,r.statusCode(),r.body());return r.body();}
    String hidden(String html) {
        var pairs=new ArrayList<String>();var inputs=Pattern.compile("<input\\b[^>]*>").matcher(html);
        while(inputs.find()) {
            var attributes=new HashMap<String,String>();var a=Pattern.compile("([\\w-]+)=\"([^\"]*)\"").matcher(inputs.group());
            while(a.find())attributes.put(a.group(1),decode(a.group(2)));
            if("hidden".equals(attributes.get("type"))&&attributes.containsKey("name"))pairs.add(enc(attributes.get("name"))+"="+enc(attributes.getOrDefault("value","")));
        }
        return String.join("&",pairs);
    }
    String fields(String... kv){var p=new ArrayList<String>();for(int i=0;i<kv.length;i+=2)p.add(enc(kv[i])+"="+enc(kv[i+1]));return String.join("&",p);}
    String execute(String base,String values) throws Exception {
        get(base);String confirm=post(base+"/confirm",values+"&from=todo-dashboard");
        assertTrue(confirm.contains("name=\"token\""),confirm);
        var result=request(base,hidden(confirm));assertEquals(302,result.statusCode(),result.body());return confirm;
    }
    void executeNoForm(String base) throws Exception {
        String confirm=get(base+"/confirm?from=todo");var r=request(base,hidden(confirm));assertEquals(302,r.statusCode(),r.body());
    }

    @Test void allBusinessPagesAndOperationsWorkThroughHttp() throws Exception {
        assertTrue(get("/").contains("TODOダッシュボード"));
        execute("/category/add-category",fields("name","Webカテゴリ"));
        execute("/tag/add-tag",fields("name","Webタグ"));
        String category=jdbc.queryForObject("select id from category where name='Webカテゴリ'",String.class);
        String tag=jdbc.queryForObject("select id from tag where name='Webタグ'",String.class);
        assertTrue(get("/category/"+category).contains("Webカテゴリ"));assertTrue(get("/tag/"+tag).contains("Webタグ"));
        String today=LocalDate.now().toString();String due=LocalDate.now().plusDays(5).toString();
        String confirm=execute("/todo/register-todo",fields("title","WebTODO","summary","HTTPで全画面を確認","category",category,"tags",tag,"due",due,"details","一行目\n二行目"));
        String id=jdbc.queryForObject("select id from todo where title='WebTODO'",String.class);
        long count=jdbc.queryForObject("select count(*) from todo",Long.class);
        assertEquals(302,request("/todo/register-todo",hidden(confirm)).statusCode());
        assertEquals(count,jdbc.queryForObject("select count(*) from todo",Long.class));
        String detail=get("/todo/"+id);assertTrue(detail.contains("Webカテゴリ")&&detail.contains("Webタグ")&&detail.contains("未着手"),detail);
        execute("/todo/"+id+"/add-todo-comment",fields("date",today,"body","コメントを保持"));
        execute("/todo/"+id+"/add-todo-task",fields("title","作業を進める"));
        String task=jdbc.queryForObject("select id from todo_task where todo_id=?",String.class,id);
        executeNoForm("/todo/"+id+"/start-todo");
        execute("/todo/"+id+"/suspend-todo",fields("date",today,"reason","確認中","resumeOn",due));
        execute("/todo/"+id+"/resume-todo",fields("date",today,"note","確認済み"));
        executeNoForm("/todo-task/"+task+"/todo-task.finish");
        execute("/todo/"+id+"/edit-todo",fields("title","WebTODO更新","summary","更新した概要","clear.details","true"));
        execute("/todo/"+id+"/complete-todo",fields("date",today,"result","全画面を確認した"));
        detail=get("/todo/"+id);assertTrue(detail.contains("完了")&&detail.contains("コメントを保持")&&detail.contains("作業を進める"),detail);
        assertEquals("CLOSED",jdbc.queryForObject("select status from todo where id=?",String.class,id));
        execute("/todo/register-todo",fields("title","中止対象","summary","中止の確認"));
        String cancel=jdbc.queryForObject("select id from todo where title='中止対象'",String.class);
        execute("/todo/"+cancel+"/cancel-todo",fields("reason","不要になった"));
        assertEquals("CANCELED",jdbc.queryForObject("select status from todo where id=?",String.class,cancel));
        String dashboard=get("/?category="+category+"&tags="+tag);assertTrue(dashboard.contains("更新した概要")&&dashboard.contains("カテゴリ別の状況"),dashboard);
        assertTrue(get("/?categories.cell="+category+",CLOSED").contains("更新した概要"));
        String empty=get("/?categories.cell="+category+",OPEN");assertFalse(empty.contains("更新した概要"));
        assertTrue(empty.contains("すべてのTODO</h2><span class=\"muted\">0 件</span>"),empty);
        assertEquals(404,request("/todo/UNKNOWN",null).statusCode());
        assertEquals(405,request("/todo/"+id+"/start-todo",null).statusCode());
    }

    @Test void validationBackAndEscapingPreserveInput() throws Exception {
        String invalid=post("/todo/register-todo/confirm",fields("title","長".repeat(101),"summary","","due","not-a-date"));
        assertTrue(invalid.contains("100文字以内")&&invalid.contains("概要を入力してください")&&invalid.contains("not-a-date"),invalid);
        String confirm=post("/todo/register-todo/confirm",fields("title","<script>alert(1)</script>","summary","  前後を保持  ","details","一行目\n二行目"));
        assertFalse(confirm.contains("<script>alert(1)</script>"));assertTrue(confirm.contains("&lt;script&gt;"));
        String back=post("/todo/register-todo/back",hidden(confirm));assertTrue(back.contains("  前後を保持  ")&&back.contains("一行目"),back);
        execute("/todo/register-todo",fields("title","入力保持の対象","summary","戻るの確認"));
        String id=jdbc.queryForObject("select id from todo where title='入力保持の対象'",String.class);
        String kept=post("/todo/"+id+"/edit-todo/back",fields("title","保持するタイトル","details","一行目\n二行目"));
        assertTrue(kept.contains("保持するタイトル")&&kept.contains("一行目"),kept);
        assertEquals("入力保持の対象",jdbc.queryForObject("select title from todo where id=?",String.class,id));
    }

    List<String> commentBodies(String id){return jdbc.queryForList("select body from todo_comments where todo_id=? order by seq",String.class,id);}

    @Test void commentRowsAreEditedAndDeletedFromTheDetailPage() throws Exception {
        String today=LocalDate.now().toString();
        execute("/todo/register-todo",fields("title","コメントの対象","summary","行の操作"));
        String id=jdbc.queryForObject("select id from todo where title='コメントの対象'",String.class);
        execute("/todo/"+id+"/add-todo-comment",fields("date",today,"body","一つ目"));
        execute("/todo/"+id+"/add-todo-comment",fields("date",today,"body","二つ目"));
        // 詳細画面のコメントの行に、番号（並び順）を運ぶボタンが出る。一覧の行には出ない
        String detail=decode(get("/todo/"+id));
        for(String row:List.of("1","2")) {
            assertTrue(Pattern.compile("/todo/"+id+"/edit-todo-comment\\?[^\"]*\\brow="+row+"\\b").matcher(detail).find(),detail);
            assertTrue(Pattern.compile("/todo/"+id+"/delete-todo-comment/confirm\\?[^\"]*\\brow="+row+"\\b").matcher(detail).find(),detail);
        }
        String dashboard=get("/");assertFalse(dashboard.contains("edit-todo-comment")||dashboard.contains("delete-todo-comment"),dashboard);
        String origin="&from=todo&origin="+id;
        // 編集: 入力画面に選んだ行の今の値が入り、番号は hidden で持ち回す
        String base="/todo/"+id+"/edit-todo-comment";
        String input=get(base+"?row=2"+origin);
        assertTrue(input.contains("二つ目")&&!input.contains("一つ目"),input);
        assertTrue(hidden(input).contains("row=2"),hidden(input));
        String unchanged=post(base+"/confirm",fields("row","2","date",today,"body","二つ目")+origin);
        assertTrue(unchanged.contains("変更はありません。"),unchanged);
        String confirm=post(base+"/confirm",fields("row","2","date",today,"body","直した <&>")+origin);
        assertTrue(confirm.contains("name=\"token\"")&&confirm.contains("直した &lt;&amp;&gt;"),confirm);
        assertEquals(List.of("一つ目","二つ目"),commentBodies(id));
        assertEquals(302,request(base,hidden(confirm)).statusCode());
        assertEquals(List.of("一つ目","直した <&>"),commentBodies(id));
        // 削除: 確認画面から始まり、対象の行を見せて、その 1 件だけを消す
        String remove="/todo/"+id+"/delete-todo-comment";
        String removing=get(remove+"/confirm?row=1"+origin);
        assertTrue(removing.contains("一つ目")&&removing.contains("name=\"token\""),removing);
        assertEquals(302,request(remove,hidden(removing)).statusCode());
        assertEquals(List.of("直した <&>"),commentBodies(id));
        assertEquals(2L,jdbc.queryForObject("select seq from todo_comments where todo_id=?",Long.class,id));
        // 無い番号・番号なしは、画面を出す前に 404
        assertEquals(404,request(base+"?row=1"+origin,null).statusCode());
        assertEquals(404,request(remove+"/confirm?row=9"+origin,null).statusCode());
        assertEquals(404,request(remove+"/confirm?from=todo&origin="+id,null).statusCode());
        assertEquals(List.of("直した <&>"),commentBodies(id));
    }

    @Test void tokensAreIndependentAndStaleStateIsRechecked() throws Exception {
        String first=post("/todo/register-todo/confirm",fields("title","タブ一","summary","並行確認"));
        String second=post("/todo/register-todo/confirm",fields("title","タブ二","summary","並行確認"));
        assertEquals(302,request("/todo/register-todo",hidden(first)).statusCode());
        assertEquals(302,request("/todo/register-todo",hidden(second)).statusCode());
        assertEquals(2,jdbc.queryForObject("select count(*) from todo where title in ('タブ一','タブ二')",Integer.class));
        String id=jdbc.queryForObject("select id from todo where title='タブ一'",String.class);
        String base="/todo/"+id+"/start-todo";
        String stale=get(base+"/confirm?from=todo");
        execute("/todo/"+id+"/cancel-todo",fields("reason","別のタブで中止"));
        String refused=post(base,hidden(stale));assertTrue(refused.contains("notice-errors")&&refused.contains("name=\"token\""),refused);
        assertEquals("CANCELED",jdbc.queryForObject("select status from todo where id=?",String.class,id));
        var replay=request(base,hidden(stale));assertEquals(302,replay.statusCode());assertEquals("/todo/"+id,URI.create(replay.headers().firstValue("location").orElseThrow()).getPath());
        String external=post("/todo/register-todo/confirm",fields("title","安全な戻り先","summary","起点","from","https://outside.invalid/"));
        var result=request("/todo/register-todo",hidden(external));assertEquals(302,result.statusCode());URI location=URI.create(result.headers().firstValue("location").orElseThrow());assertEquals("/",location.getPath());assertTrue(location.getHost()==null||location.getHost().equals("localhost"));
        assertTrue(get("/").contains("TODOを登録しました"));assertFalse(get("/").contains("TODOを登録しました"));
    }
}
