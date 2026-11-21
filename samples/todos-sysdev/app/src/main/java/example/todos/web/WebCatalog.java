package example.todos.web;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** 保存した確定設計を表示用に読む。業務モデルや補正をここで再計算しない。 */
@Component
public class WebCatalog {
    final Map<String,Object> data;
    final Map<String,Map<String,Object>> pages = new LinkedHashMap<>();
    final Map<String,Map<String,Object>> operations = new LinkedHashMap<>();
    final Map<String,Map<String,Object>> inputs = new LinkedHashMap<>();
    final Map<String,Map<String,Object>> forms = new LinkedHashMap<>();
    final Map<String,Map<String,Object>> methods = new LinkedHashMap<>();
    final Map<String,Map<String,Object>> rows = new LinkedHashMap<>();
    final Map<String,String> messages = new LinkedHashMap<>();
    final Map<String,String> destinations = new LinkedHashMap<>();
    final List<Map<String,Object>> routes = new ArrayList<>();

    public WebCatalog() throws java.io.IOException {
        try (var in = new ClassPathResource("web-model.json").getInputStream()) {
            data = map(JsonMapper.builder().build().readValue(in, Map.class));
        }
        for (var p : maps(data.get("pages"))) pages.put(s(p,"key"), p);
        for (var p : maps(data.get("operations"))) operations.put(s(p,"action"), p);
        for (var p : maps(data.get("inputs"))) inputs.put(s(p,"id"), p);
        for (var p : maps(data.get("forms"))) forms.put(s(p,"className"), p);
        for (var p : maps(data.get("rows"))) rows.put(s(p,"className"), p);
        for (var m : maps(data.get("reads"))) for (var p : maps(m.get("methods"))) methods.put(s(m,"name")+"."+s(p,"name"),p);
        for(var service:maps(data.get("readServices")))for(var method:maps(service.get("methods")))if(yes(method.get("detail")))methods.put(s(method,"call"),methods.get(s(maps(method.get("queries")).getFirst(),"call")));
        for (var p : maps(map(data.get("messages")).get("messages"))) messages.put(s(p,"key"),s(p,"text"));
        for (var t : maps(data.get("transitions"))) for(var p : maps(t.get("screens")))
            if(p.containsKey("url")) destinations.put(s(p,"id"),s(p,"url"));
        for (var controller : maps(data.get("controllers"))) {
            for(var show : maps(controller.get("screens"))) routes.add(new LinkedHashMap<>(show));
            for(var action : maps(controller.get("handlers"))) {
                int n=0;
                for(var step : maps(action.get("steps"))) {
                    var route=new LinkedHashMap<>(step);
                    route.put("action",s(action,"action")); route.put("form",s(action,"form"));
                    String screen=s(step,"screen");
                    route.put("stage",screen.isEmpty()?"execute":screen.endsWith("/confirm")?"confirm":n==0?"input":"back");
                    routes.add(route); n++;
                }
            }
        }
        // 同じ URL と HTTP メソッドの二重定義は起動時にも拒否する。
        var requests=new HashSet<String>();
        for(var r:routes) if(!requests.add(s(r,"request"))) throw new IllegalStateException("画面の入口が重複しています: "+s(r,"request"));
        routes.sort(Comparator.<Map<String,Object>>comparingInt(r->(int)s(r,"request").chars().filter(c->c=='{').count()).thenComparing(Comparator.comparingInt((Map<String,Object> r)->s(r,"request").length()).reversed()));
    }

    public String message(String key,Map<String,?> values) {
        String text=messages.getOrDefault(key,key);
        for(var e:values.entrySet()) text=text.replace("{"+e.getKey()+"}",Objects.toString(e.getValue(),""));
        return text;
    }
    public String sessionKey(String role) {return maps(map(data.get("session")).get("attributes")).stream().filter(a->s(a,"role").equals(role)).map(a->s(a,"key")).findFirst().orElseThrow(()->new IllegalStateException("セッションの役割がありません: "+role));}
    public String homeScreen(){return maps(data.get("transitions")).stream().map(t->s(t,"start")).filter(destinations::containsKey).findFirst().orElseThrow(()->new IllegalStateException("一覧の起点がありません"));}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object v) { return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of(); }
    @SuppressWarnings("unchecked") public static List<Object> list(Object v) { return v instanceof List<?>?(List<Object>)v:List.of(); }
    public static List<Map<String,Object>> maps(Object v) { return list(v).stream().map(WebCatalog::map).toList(); }
    public static String s(Map<String,Object> m,String key) { return Objects.toString(m.get(key),""); }
    public static boolean yes(Object v) { return Boolean.parseBoolean(Objects.toString(v,"false")); }
    public static Object at(Object value,String path) {
        if(path==null||path.isEmpty()||path.equals("."))return value;
        for(String part:path.replace("[",".").replace("]","").split("\\.")) {
            if(value instanceof Map<?,?> m) value=m.get(part);
            else if(value instanceof List<?> l) {try{int n=Integer.parseInt(part); value=n>=0&&n<l.size()?l.get(n):null;}catch(NumberFormatException e){return null;}}
            else return null;
        }
        return value;
    }
}
