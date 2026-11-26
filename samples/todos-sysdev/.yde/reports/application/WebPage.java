package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.validation.BindingResult;
import org.springframework.web.util.UriUtils;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.NotFoundException;
import static {#each config.package}{.}{#if more}.{/if}{/each}.web.WebCatalog.*;

/** Thymeleaf の共通部品に渡す表示値。項目の対応は確定した Form と Mapper から読む。 */
public class WebPage {{
    private final WebCatalog catalog;
    private final WebReads reads;
    private final HttpServletRequest request;
    private final LocalDate today;
    private final Map<String,Object> cache=new HashMap<>();
    public String receiverId="",action="",screenKey="",from="",originId="",flow="",token="",notice="";
    public Map<String,Object> operation=Map.of(),input=Map.of(),formDef=Map.of(),self=Map.of();
    public Object form;
    public BindingResult binding;
    public final List<String> errors=new ArrayList<>();
    private Map<String,Object> screen=Map.of();
    public WebPage(WebCatalog catalog,WebReads reads,HttpServletRequest request,LocalDate today) {{
        this.catalog=catalog;this.reads=reads;this.request=request;this.today=today;
    }}
    public void setScreen(String key) {{
        screenKey=key;var page=catalog.pages.get(key);
        if(page==null)throw new IllegalStateException("画面がありません: "+key);
        screen=map(page.get("screen"));
    }}
    public String template(){{return s(catalog.pages.get(screenKey),"template");}}
    public String title(){{return s(map(screen.get("page")),"title");}}
    public String brand(){{return s(map(map(catalog.data.get("theme")).get("theme")),"name");}}
    public String homeUrl(){{return request.getContextPath()+catalog.destinations.get(catalog.homeScreen());}}
    public String entityUrl(String entity,Object id){{return link(entity,id);}}
    public String redrawUrl(){{return catalog.routes.stream().filter(r->s(r,"action").equals(action)&&s(r,"stage").equals("back")).map(r->url(r.get("request"))).findFirst().orElse(inputUrl());}}
    public String clearFlag(Map<String,Object> node){{String flag=s(map(node.get("clearable")),"flag");if(!flag.isEmpty())return flag;return maps(map(input.get("input")).get("items")).stream().filter(f->s(f,"key").equals(s(node,"item"))).map(f->s(f,"clearedBy")).findFirst().orElse("");}}
    public Object node(String path){{return at(screen,path);}}
    public String message(String key){{return catalog.message(key,Map.of());}}
    public String inputUrl(){{return catalog.routes.stream().filter(r->s(r,"action").equals(action)&&s(r,"stage").equals("input")).map(r->url(r.get("request"))).findFirst().orElse("/");}}
    public Map<String,Object> parameters() {{
        var p=new LinkedHashMap<String,Object>();
        for(var method:catalog.methods.values())for(var param:maps(method.get("parameters"))){{
            String key=s(param,"name"),type=s(param,"type");String[] values=request.getParameterValues(key);
            if(type.equals("java.util.List<java.lang.String>"))p.put(key,values==null?List.of():Arrays.asList(values));
            else if(type.equals("java.time.LocalDate"))p.put(key,key.equals("today")?date():values==null?null:LocalDate.parse(values[0]));
            else if(type.equals("java.lang.String"))p.put(key,values==null?null:empty(values[0]));
        }}
        p.put("id",receiverId);
        for(var selection:matrixSelections())for(var field:filterFields())if(s(map(field.get("candidates")),"entity").equals(s(map(selection.matrix.get("rows")),"entity")))p.put(s(field,"item"),s(field,"control").equals("checkboxes")?List.of(selection.rowId):selection.rowId);
        return p;
    }}
    private List<Map<String,Object>> filterFields(){{var result=new ArrayList<Map<String,Object>>();for(var node:maps(map(screen.get("page")).get("body")))for(var child:maps(map(node.get("filters")).get("body"))){{if(child.containsKey("field"))result.add(map(child.get("field")));if(child.containsKey("choices"))result.add(map(child.get("choices")));}}return result;}}
    private String dateField(){{return filterFields().stream().filter(f->s(f,"control").equals("date")).map(f->s(f,"item")).findFirst().orElse("");}}
    private LocalDate date() {{String d=request.getParameter(dateField());try{{return d==null||d.isEmpty()?today:LocalDate.parse(d);}}catch(java.time.DateTimeException ex){{return today;}}}}
    private Object read(String call,Map<String,Object> params) {{String key=call+params;return cache.computeIfAbsent(key,k->reads.read(call,params));}}
    private String detailRead(String entity) {{
        var p=catalog.pages.get(entity);
        if(p!=null)return s(map(p.get("screen")),"read");
        return catalog.routes.stream().filter(r->s(r,"action").equals(action)).flatMap(r->list(r.get("reads")).stream()).filter(v->v instanceof String&&catalog.methods.containsKey(v)&&maps(catalog.methods.get(v).get("parameters")).stream().anyMatch(parameter->s(parameter,"name").equals("id"))).map(Object::toString).findFirst().orElseThrow();
    }}
    public void loadEntity(String entity,String id) {{
        String call=catalog.routes.stream().filter(r->s(r,"screen").equals(entity)).map(r->s(r,"service")).findFirst().orElse(detailRead(entity));
        Object row=read(call,Map.of("id",id));
        if(row==null)throw new NotFoundException(entity,id);self=map(row);
    }}
    public void loadDashboard(String service) {{var params=parameters();for(var e:map(read(service,params)).entrySet())cache.put(e.getKey()+params,e.getValue());}}
    public String count(Map<String,Object> node){{
        if(!node.containsKey("count"))return "";
        if(!matrixSelections().isEmpty())for(var child:maps(node.get("body")))if(child.containsKey("table"))return rows(map(child.get("table"))).size()+" 件";
        return Objects.toString(read(s(node,"count"),parameters()),"0")+" 件";
    }}
    private String empty(String v){{return v==null||v.isEmpty()?null:v;}}
    private String enc(Object v){{return UriUtils.encode(Objects.toString(v,""),java.nio.charset.StandardCharsets.UTF_8);}}
    public String url(Object requestOrUrl) {{
        String value=Objects.toString(requestOrUrl,"");if(value.contains(" "))value=value.substring(value.indexOf(' ')+1);
        if(!value.startsWith("/")||value.startsWith("//"))throw new IllegalArgumentException("画面の URL が不正です");
        return request.getContextPath()+value.replace("{{id}}",enc(receiverId));
    }}
    public String allowedFrom(String wanted) {{
        var starts=maps(input.get("starts")).stream().map(m->s(m,"screen")).toList();
        if(wanted!=null&&starts.contains(wanted)&&catalog.destinations.containsKey(wanted))return wanted;
        return starts.stream().filter(catalog.destinations::containsKey).findFirst().orElse(catalog.homeScreen());
    }}
    public String originId(String supplied) {{
        if(from.equals(s(input,"target")))return receiverId;
        // 入れ子の操作の起点は、読んだ行の親参照から取り出す。
        if(catalog.destinations.getOrDefault(from,"").contains("{{id}}")&&catalog.pages.containsKey(from)){{String table=s(catalog.methods.get(detailRead(from)),"tableId");Object parent=map(self.get("_parents")).get(table);if(parent!=null)return parent.toString();}}
        return supplied==null?"":supplied;
    }}
    public String returnUrl() {{
        String destination=catalog.destinations.getOrDefault(from,catalog.destinations.get(catalog.homeScreen()));
        if(destination.contains("{{id}}")&&originId.isEmpty())destination=catalog.destinations.get(catalog.homeScreen());
        return request.getContextPath()+destination.replace("{{id}}",enc(originId));
    }}
    public String actionUrl(Map<String,Object> button,Map<String,Object> row) {{
        if(s(button,"to").contains("起点"))return returnUrl();
        String value=s(button,"url");if(value.isEmpty())value=s(button,"action");if(value.isEmpty())value=s(button,"to");
        if(!value.contains("/"))value=catalog.destinations.getOrDefault(value,"/");
        String id=Objects.toString(row.getOrDefault("id",receiverId),"");
        String result=url(value.replace("{{id}}",enc(id)));
        // 並びの行のボタンは、行の並び順の値を、操作の Form の項目名で運ぶ（人は番号を入力しない）。
        var carry=map(button.get("row"));
        if(!carry.isEmpty())result+=(result.contains("?")?"&":"?")+enc(formField(s(button,"operation"),s(carry,"param")))+"="+enc(row.get(s(carry,"item")));
        if(!s(button,"kind").equals("submit")&&!s(button,"action").startsWith("POST ")) {{
            String origin=screenKey.contains("/")?from:screenKey;
            String originKey=screenKey.contains("/")?originId:receiverId;
            result+=(result.contains("?")?"&":"?")+"from="+enc(origin)+"&origin="+enc(originKey);
        }}
        return result;
    }}
    public List<Map<String,Object>> actions(Object definition,Map<String,Object> row) {{
        Object source=definition instanceof Map<?,?>?map(definition).get("list"):definition;
        return maps(source).stream().filter(a->{{Object condition=a.get("shownWhen");return !(condition instanceof List<?>)||list(condition).contains(row.get("status"));}}).toList();
    }}
    public Map<String,Object> self(){{return self;}}
    public String fieldName(String item) {{return fieldName(item,formDef);}}
    private String fieldName(String item,Map<String,Object> definition) {{
        if(form==null)return item;
        int dot=item.indexOf('.');String head=dot<0?item:item.substring(0,dot);String tail=dot<0?"":item.substring(dot+1);
        String key=head.replaceAll("\\[.*]","");String suffix=head.substring(key.length());
        var field=maps(definition.get("fields")).stream().filter(f->s(f,"item").equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("入力項目がありません: "+item));
        String result=s(field,"name")+suffix;
        return tail.isEmpty()?result:result+"."+fieldName(tail,nested(field));
    }}
    private Map<String,Object> nested(Map<String,Object> field) {{
        String type=s(field,"type").replace("List<","").replace(">","");
        return maps(formDef.get("classes")).stream().filter(c->s(c,"name").equals(type)).findFirst().orElse(Map.of());
    }}
    public List<String> allowedFields() {{var out=new ArrayList<String>();allowed(formDef,"",out);return out;}}
    private void allowed(Map<String,Object> def,String prefix,List<String> out) {{
        for(var f:maps(def.get("fields"))) {{
            String key=prefix+s(f,"name");var child=nested(f);boolean many=s(f,"type").startsWith("List<");
            if(!child.isEmpty())allowed(child,key+(many?"[*]":"")+".",out);
            else {{out.add(key);if(many)out.add(key+"[*]");}}
        }}
    }}
    private String formField(String operation,String item) {{
        var def=catalog.forms.getOrDefault(s(catalog.operations.getOrDefault(operation,Map.of()),"formClass"),Map.of());
        return maps(def.get("fields")).stream().filter(f->s(f,"item").equals(item)).map(f->s(f,"name")).findFirst().orElse(item);
    }}
    /** 番号で指す行の決まり（どの並びの、どの受け取る値で、行のどの項目と突き合わせるか）。いまの画面の定義から読む。 */
    private Map<String,Object> rowSpec() {{
        for(var node:maps(map(screen.get("page")).get("body")))for(var v:node.values()){{var r=map(map(v).get("row"));if(r.containsKey("table"))return r;}}
        return Map.of();
    }}
    /** 番号で指す行: 受け手の並びのうち、並び順の項目が受け取った番号と一致する 1 件。無ければ見つからない扱い（データの層と同じ断り）。 */
    public Map<String,Object> selectedRow() {{
        var spec=rowSpec();if(spec.isEmpty())return Map.of();
        String wanted=request.getParameter(fieldName(s(spec,"param")));
        if(wanted!=null)for(var r:maps(self.get(s(spec,"table"))))if(Objects.toString(r.get(s(spec,"item")),"").equals(wanted.trim()))return r;
        throw new NotFoundException(s(input,"target")+"."+s(spec,"table"),receiverId);
    }}
    /** 受け手を入れ子で持つ親の行。受け手の行が持つ親のキー（_parents）から、親の詳細の読出しで読む。 */
    private Map<String,Object> ownerRow() {{
        var parents=map(self.get("_parents"));
        for(var page:catalog.pages.values()) {{
            String call=s(map(page.get("screen")),"read");var method=catalog.methods.get(call);
            if(method==null)continue;
            Object id=parents.get(s(method,"tableId"));
            if(id!=null){{Object row=read(call,Map.of("id",id.toString()));if(row!=null)return map(row);}}
        }}
        throw new IllegalArgumentException("親の行を読めません");
    }}
    public Map<String,Object> initialValues() {{
        var logical=new LinkedHashMap<String,Object>();
        var rowSpec=rowSpec();
        if(!rowSpec.isEmpty()){{selectedRow();logical.put(s(rowSpec,"param"),request.getParameter(fieldName(s(rowSpec,"param"))));}}
        // 人が入れる入力のはじめの値と、画面が運ぶ値（読んだときの版など）を、同じ読み方で入れる。
        var sources=new LinkedHashMap<String,Object>(map(map(input.get("input")).get("initial")));sources.putAll(map(map(input.get("input")).get("carried")));
        for(var entry:sources.entrySet()) {{
            var exp=map(entry.getValue());String source=s(exp,"$of"),path=s(exp,"$copy");
            // 並びの番号で指す読み出し（comments[$row].date）は、番号の指す行から読む。
            int open=path.indexOf("[$");
            if(source.equals("self")&&open>=0){{String rest=path.substring(path.indexOf(']',open)+1);logical.put(entry.getKey(),at(selectedRow(),rest.startsWith(".")?rest.substring(1):rest));}}
            else if(source.equals("self"))logical.put(entry.getKey(),at(self,path));
            else if(source.equals("owner"))logical.put(entry.getKey(),at(ownerRow(),path));
            else if(source.equals("context")&&path.equals("today"))logical.put(entry.getKey(),today.toString());
            else throw new IllegalArgumentException("未対応の初期値です: "+entry.getKey());
        }}
        var out=new LinkedHashMap<String,Object>();flatten(logical,"",out);return out;
    }}
    private void flatten(Object value,String path,Map<String,Object> out) {{
        if(value instanceof Map<?,?> m)for(var e:m.entrySet())flatten(e.getValue(),path.isEmpty()?e.getKey().toString():path+"."+e.getKey(),out);
        else if(value instanceof List<?> l) {{for(int i=0;i<l.size();i++)flatten(l.get(i),path+"["+i+"]",out);}}
        else if(value!=null) {{try{{out.put(fieldName(path),value);}}catch(IllegalArgumentException ignored){{/* 行の親キー・連番は入力へ持ち込まない。 */}}}}
    }}
    public Object value(String item) {{
        if(form==null) {{if(item.equals(dateField()))return date().toString();String[] values=request.getParameterValues(item);return values==null?null:values.length==1?values[0]:List.of(values);}}
        String name=fieldName(item);
        try {{if(binding!=null)return binding.getFieldValue(name);return new BeanWrapperImpl(form).getPropertyValue(name);}}
        catch(org.springframework.beans.NullValueInNestedPathException ex){{return null;}}
    }}
    public String fieldError(String item) {{
        if(binding==null)return "";var e=binding.getFieldError(fieldName(item));
        if(e==null)return "";return e.isBindingFailure()?"入力した値の形式を確認してください。":Objects.toString(e.getDefaultMessage(),"入力を確認してください。");
    }}
    public List<String> validationMessages(){{return binding==null?List.of():binding.getAllErrors().stream().map(e->e instanceof org.springframework.validation.FieldError f&&f.isBindingFailure()?"入力した値の形式を確認してください。":Objects.toString(e.getDefaultMessage(),"入力を確認してください。")).toList();}}
    public void violation({#each config.package}{.}{#if more}.{/if}{/each}.service.error.BusinessViolation violation){{String text=catalog.message(violation.key(),violation.values());var target=maps(operation.get("fieldTargets")).stream().filter(t->s(t,"target").equals(violation.field())).findFirst();if(binding!=null&&target.isPresent())binding.rejectValue(s(target.get(),"source"),"business",text);else errors.add(text);}}
    public boolean checked(String item,Object candidate) {{Object v=value(item);return v instanceof Collection<?> c?c.stream().anyMatch(x->Objects.toString(x,"").equals(Objects.toString(candidate,""))):Objects.toString(v,"").equals(Objects.toString(candidate,""));}}
    public List<Map<String,Object>> candidates(Map<String,Object> node) {{var c=map(node.get("candidates"));return maps(read(s(c,"read"),parameters()));}}
    public List<Map<String,String>> hidden(Map<String,Object> node) {{
        var out=new ArrayList<Map<String,String>>();
        if(form!=null)for(var f:maps(formDef.get("fields")))if(s(f,"type").startsWith("List<")&&!nested(f).isEmpty()&&new BeanWrapperImpl(form).getPropertyValue(s(f,"name"))!=null)addHidden(out,"_present."+s(f,"name"),"true");
        // 入力画面: 画面の定義が hidden と書いた Form の項目（行のボタンが運んだ番号）を持ち回す。
        if(!screenKey.endsWith("/confirm")&&form!=null)for(Object h:list(node.get("hidden"))){{String item=h.toString();if(maps(formDef.get("fields")).stream().anyMatch(f->s(f,"item").equals(item)))addHidden(out,fieldName(item),value(item));}}
        if(screenKey.endsWith("/confirm")&&form!=null) {{
            for(String field:allowedFields()) {{
                if(field.contains("[*]"))continue;
                Object value;try{{value=new BeanWrapperImpl(form).getPropertyValue(field);}}catch(org.springframework.beans.NullValueInNestedPathException ex){{continue;}}
                addHidden(out,field,value);
            }}
            for(var f:maps(formDef.get("fields")))if(s(f,"type").startsWith("List<")&&!nested(f).isEmpty()) {{
                var rows=list(new BeanWrapperImpl(form).getPropertyValue(s(f,"name")));
                for(int i=0;i<rows.size();i++)for(var child:maps(nested(f).get("fields"))) addHidden(out,s(f,"name")+"["+i+"]."+s(child,"name"),new BeanWrapperImpl(rows.get(i)).getPropertyValue(s(child,"name")));
            }}
        }}
        if(!action.isEmpty()) {{
            addHidden(out,"from",from);addHidden(out,"origin",originId);
            if(!flow.isEmpty()){{addHidden(out,"flow",flow);addHidden(out,"token",token);}}
        }}
        return out;
    }}
    public void restoreCollections(){{if(form==null)return;var bean=new BeanWrapperImpl(form);for(var f:maps(formDef.get("fields")))if(s(f,"type").startsWith("List<")&&!nested(f).isEmpty()&&"true".equals(request.getParameter("_present."+s(f,"name")))&&bean.getPropertyValue(s(f,"name"))==null)bean.setPropertyValue(s(f,"name"),new ArrayList<>());}}
    private void addHidden(List<Map<String,String>> out,String name,Object v) {{
        if(v instanceof Collection<?> c){{for(Object x:c)addHidden(out,name,x);}}
        else if(v!=null)out.add(Map.of("name",name,"value",v.toString()));
    }}
    public List<Map<String,Object>> rows(Map<String,Object> node) {{
        if(node.containsKey("read")) {{
            var rows=maps(read(s(node,"read"),parameters()));
            var selections=matrixSelections();
            return rows.stream().filter(r->selections.stream().allMatch(f->!r.containsKey(f.axisItem)||f.axisValue.equals(r.get(f.axisItem)))).toList();
        }}
        return maps(self.get(s(node,"of")));
    }}
    private record MatrixSelection(Map<String,Object> matrix,String rowId,String axisItem,String axisValue) {{}}
    private Map<String,Map<String,Object>> matrices(){{var result=new LinkedHashMap<String,Map<String,Object>>();collectMatrices(map(screen.get("page")).get("body"),result);return result;}}
    private void collectMatrices(Object body,Map<String,Map<String,Object>> result){{for(var node:maps(body)){{var section=map(node.get("section"));for(var child:maps(section.get("body")))if(child.containsKey("matrix"))result.put(s(section,"id"),map(child.get("matrix")));collectMatrices(section.get("body"),result);}}}}
    private List<MatrixSelection> matrixSelections(){{
        var result=new ArrayList<MatrixSelection>();
        for(var entry:matrices().entrySet()){{
            String value=request.getParameter(entry.getKey()+".cell");if(value==null||!value.contains(","))continue;
            var method=catalog.methods.get(s(entry.getValue(),"read"));
            for(var axis:maps(map(method.get("raw")).get("groupBy")))if(axis.containsKey("values"))result.add(new MatrixSelection(entry.getValue(),value.substring(0,value.indexOf(',')),s(axis,"item"),value.substring(value.indexOf(',')+1)));
        }}
        return result;
    }}
    public List<Integer> rowNumbers(Map<String,Object> node) {{Object v=value(s(node,"item"));int count=v instanceof List<?> l?l.size():0;return java.util.stream.IntStream.range(0,count).boxed().toList();}}
    public Map<String,Object> rowField(Map<String,Object> column,String item,int index) {{var f=new LinkedHashMap<>(map(column.get("field")));f.put("item",item+"["+index+"]."+s(f,"item"));return f;}}
    public void editRows(String command) {{
        String[] parts=command.split(":");if(parts.length!=2)return;
        String item=parts[0];var f=maps(formDef.get("fields")).stream().filter(v->s(v,"item").equals(item)&&s(v,"type").startsWith("List<")&&!nested(v).isEmpty()).findFirst().orElseThrow();
        var bean=new BeanWrapperImpl(form);var values=new ArrayList<>(list(bean.getPropertyValue(s(f,"name"))));
        if(parts[1].equals("add")) {{if(values.size()>=200){{errors.add("行は200件まで追加できます。");return;}}try{{String type=s(nested(f),"name");var nested=Arrays.stream(form.getClass().getDeclaredClasses()).filter(c->c.getSimpleName().equals(type)).findFirst().orElseThrow();values.add(nested.getDeclaredConstructor().newInstance());}}catch(ReflectiveOperationException e){{throw new IllegalStateException(e);}}}}
        else {{int n=Integer.parseInt(parts[1]);if(n<0||n>=values.size())throw new IllegalArgumentException("削除する行がありません");values.remove(n);}}
        bean.setPropertyValue(s(f,"name"),values);
    }}
    public List<Map<String,Object>> facts(Map<String,Object> node) {{
        var result=new ArrayList<Map<String,Object>>();boolean change=s(node,"kind").equals("changes");
        // 番号で指す行の表示と比較は、受け手ではなく、その行の値を今の値にする。
        var base=node.containsKey("row")?selectedRow():self;
        for(var f:maps(node.get("items"))) {{
            String item=s(f,"item");Object current=base.get(item);Object next=s(node,"of").equals("form")||change?logicalValue(item,base):current;
            if(f.containsKey("to")){{current=f.get("from");next=f.get("to");}}
            if(change&&Objects.equals(comparable(current),comparable(next)))continue;
            var row=new LinkedHashMap<>(f);row.put("cells",cells(f,next));if(change||f.containsKey("to"))row.put("before",cells(f,current));result.add(row);
        }}
        return result;
    }}
    private Object logicalValue(String item,Map<String,Object> base) {{
        for(var f:maps(map(input.get("input")).get("items")))if(s(f,"key").equals(item)&&f.containsKey("clearedBy")&&yes(value(s(f,"clearedBy"))))return null;
        Object value=value(item);if(value==null)return base.get(item);
        var field=maps(formDef.get("fields")).stream().filter(f->s(f,"item").equals(item)).findFirst();
        if(field.isPresent()&&!nested(field.get()).isEmpty())return logical(value,nested(field.get()));
        return value;
    }}
    private Object logical(Object value,Map<String,Object> definition){{if(value==null)return null;if(value instanceof List<?> items)return items.stream().map(v->logical(v,definition)).toList();var bean=new BeanWrapperImpl(value);var result=new TreeMap<String,Object>();for(var field:maps(definition.get("fields")))result.put(s(field,"item"),bean.getPropertyValue(s(field,"name")));return result;}}
    private Object comparable(Object v) {{
        if(v==null)return "";
        if(v instanceof Map<?,?> m){{var result=new TreeMap<String,Object>();for(var e:m.entrySet())if(!e.getKey().toString().startsWith("_")&&!list(m.get("_hidden")).contains(e.getKey()))result.put(e.getKey().toString(),comparable(e.getValue()));return result;}}
        if(v instanceof Collection<?> l)return l.stream().map(this::comparable).toList();
        return v.toString();
    }}
    public List<Map<String,String>> tableCells(Map<String,Object> node,Map<String,Object> row,Map<String,Object> column,int index) {{
        Object value=row.get(s(column,"item"));
        var method=catalog.methods.get(s(node,"read"));
        if(method!=null)for(var p:maps(method.get("projections")))if((!s(column,"item").isEmpty()&&s(column,"item").equals(s(p,"item")))||(s(column,"item").isEmpty()&&s(column,"label").equals(s(p,"label")))){{value=row.get(s(p,"property"));break;}}
        if(yes(map(column.get("link")).get("many"))) {{
            Object ids=row.get(s(column,"item"));
            if(ids==null&&row.containsKey("id")&&method!=null){{
                String tableId=s(method,"tableId");
                var detail=catalog.pages.values().stream().map(p->s(map(p.get("screen")),"read")).filter(catalog.methods::containsKey).filter(call->s(catalog.methods.get(call),"tableId").equals(tableId)).findFirst();
                if(detail.isPresent())ids=map(read(detail.get(),Map.of("id",row.get("id")))).get(s(column,"item"));
            }}
            if(ids!=null)return cells(column,ids);
        }}
        String entity=linkEntity(column);if(!entity.isEmpty()&&!yes(map(column.get("link")).get("many")))return List.of(cell(Objects.toString(value,"—"),link(entity,row.get(s(column,"item")))));
        return cells(column,value);
    }}
    private String linkEntity(Map<String,Object> node) {{Object link=node.get("link");if(link instanceof Map<?,?>)return s(map(link),"to");return Objects.toString(link,"");}}
    private String link(String entity,Object id) {{if(id==null)return "";String path=catalog.destinations.get(entity);return path==null?"":url(path.replace("{{id}}",enc(id)));}}
    private Map<String,String> cell(String text,String url){{return Map.of("text",text,"url",url);}}
    public List<Map<String,String>> cells(Map<String,Object> spec,Object value) {{
        if(value==null||value.toString().isEmpty())return List.of(cell("—",""));
        if(value instanceof Collection<?> list) {{var out=new ArrayList<Map<String,String>>();for(var item:list)out.addAll(cells(spec,item));return out.isEmpty()?List.of(cell("—","")):out;}}
        String text=value.toString();String entity=linkEntity(spec);var name=map(spec.get("name"));
        if(name.isEmpty()&&map(spec.get("link")).containsKey("name"))name=Map.of("entity",entity,"item",s(map(spec.get("link")),"name"));
        if(!name.isEmpty()) {{if(entity.isEmpty())entity=s(name,"entity");Object row=read(detailRead(entity),Map.of("id",text));if(row!=null)text=Objects.toString(map(row).get(s(name,"item")),text);}}
        for(var label:maps(spec.get("labels")))if(s(label,"value").equals(text)){{text=s(label,"label");break;}}
        var marks=map(spec.get("mark"));text=Objects.toString(marks.getOrDefault(text,text),text);
        if(spec.containsKey("format")){{try{{text=LocalDate.parse(value.toString()).format(DateTimeFormatter.ofPattern(s(spec,"format")));}}catch(java.time.DateTimeException ignored){{}}}}
        if(value instanceof Map<?,?> row)text=row.entrySet().stream().filter(e->!e.getKey().toString().startsWith("_")&&!list(row.get("_hidden")).contains(e.getKey())).map(e->Objects.toString(e.getValue(),"")).collect(java.util.stream.Collectors.joining(" / "));
        if(!value.getClass().getPackageName().startsWith("java.")&&! (value instanceof Map<?,?>)) {{var bean=new BeanWrapperImpl(value);text=Arrays.stream(bean.getPropertyDescriptors()).filter(p->!p.getName().equals("class")).map(p->Objects.toString(bean.getPropertyValue(p.getName()),"")).collect(java.util.stream.Collectors.joining(" / "));}}
        return List.of(cell(text,link(entity,value)));
    }}
    public List<Map<String,Object>> matrixColumns(Map<String,Object> node) {{
        var method=catalog.methods.get(s(node,"read"));return maps(method.get("properties")).stream().filter(p->p.containsKey("axisValue")).toList();
    }}
    public long number(Object value){{return value instanceof Number n?n.longValue():value==null?0:Long.parseLong(value.toString());}}
    public long total(Map<String,Object> node,Map<String,Object> row){{return matrixColumns(node).stream().mapToLong(c->number(row.get(s(c,"name")))).sum();}}
    public long columnTotal(Map<String,Object> node,String name){{return rows(node).stream().mapToLong(r->number(r.get(name))).sum();}}
    public long grandTotal(Map<String,Object> node){{return rows(node).stream().mapToLong(r->total(node,r)).sum();}}
    public String matrixUrl(Map<String,Object> node,Map<String,Object> row,Map<String,Object> column) {{
        String section=matrices().entrySet().stream().filter(e->e.getValue().equals(node)).map(Map.Entry::getKey).findFirst().orElseThrow();
        String destination=s(map(map(node.get("cell")).get("link")),"to");
        String result=url(catalog.destinations.get(destination))+"?"+enc(section+".cell")+"="+enc(s(row,"id")+","+s(column,"axisValue"));
        for(var field:filterFields())if(!s(field,"item").equals(dateField()))for(String value:Optional.ofNullable(request.getParameterValues(s(field,"item"))).orElse(new String[0]))result+="&"+enc(s(field,"item"))+"="+enc(value);
        return result+(dateField().isEmpty()?"":"&"+enc(dateField())+"="+date());
    }}
}}
