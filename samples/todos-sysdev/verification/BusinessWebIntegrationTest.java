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

    @Test void validationBackRowsAndEscapingPreserveInput() throws Exception {
        String invalid=post("/todo/register-todo/confirm",fields("title","長".repeat(101),"summary","","due","not-a-date"));
        assertTrue(invalid.contains("100文字以内")&&invalid.contains("概要を入力してください")&&invalid.contains("not-a-date"),invalid);
        String confirm=post("/todo/register-todo/confirm",fields("title","<script>alert(1)</script>","summary","  前後を保持  ","details","一行目\n二行目"));
        assertFalse(confirm.contains("<script>alert(1)</script>"));assertTrue(confirm.contains("&lt;script&gt;"));
        String back=post("/todo/register-todo/back",hidden(confirm));assertTrue(back.contains("  前後を保持  ")&&back.contains("一行目"),back);
        execute("/todo/register-todo",fields("title","行編集対象","summary","行の操作"));
        String id=jdbc.queryForObject("select id from todo where title='行編集対象'",String.class);
        String base="/todo/"+id+"/edit-todo";
        String rows=post(base+"/back",fields("title","保持するタイトル","_rows","comments:add"));
        assertTrue(rows.contains("comments[0].date")&&rows.contains("保持するタイトル"),rows);
        rows=post(base+"/back",fields("title","保持するタイトル","comments[0].date",LocalDate.now().toString(),"comments[0].body","保持する本文","_rows","comments:add"));
        assertTrue(rows.contains("comments[1].body")&&rows.contains("保持する本文"),rows);
        rows=post(base+"/back",fields("title","保持するタイトル","comments[0].body","消す本文","comments[1].body","残す本文","_rows","comments:0"));
        assertFalse(rows.contains("消す本文"));assertTrue(rows.contains("残す本文"),rows);
    }

    @Test void partialRowsAndExplicitEmptyCollectionSurviveConfirmation() throws Exception {
        execute("/todo/register-todo",fields("title","空配列の対象","summary","コメント編集"));
        String id=jdbc.queryForObject("select id from todo where title='空配列の対象'",String.class);
        execute("/todo/"+id+"/add-todo-comment",fields("date",LocalDate.now().toString(),"body","元のコメント"));
        String base="/todo/"+id+"/edit-todo";
        String initial=get(base);assertTrue(initial.contains("元のコメント"),initial);
        String unchanged=post(base+"/confirm",fields("comments[0].date",LocalDate.now().toString(),"comments[0].body","元のコメント"));
        assertTrue(unchanged.contains("変更はありません。"),unchanged);
        var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+base+"/back"))
            .header("HX-Request","true").header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(fields("comments[0].body","元のコメント","_rows","comments:0"))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().startsWith("<main"),response.body());
        assertFalse(response.body().contains("<!doctype"));assertTrue(response.body().contains("name=\"_present.comments\""));
        String confirm=post(base+"/confirm",hidden(response.body()));
        assertTrue(confirm.contains("元のコメント"),confirm);
        assertEquals(302,request(base,hidden(confirm)).statusCode());
        assertEquals(0,jdbc.queryForObject("select count(*) from todo_comments where todo_id=?",Integer.class,id));
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
