package example.todos.web;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.BeanWrapperImpl;
import example.todos.data.mapper.*;
import example.todos.data.model.*;
import example.todos.service.*;
import static example.todos.web.WebCatalog.*;

/** 確定した Mapper に限定した読出し。更新 SQL は登録しない。 */
@Component
public class WebReads {
    private final WebCatalog catalog;
    private final Map<String,java.util.function.Function<Map<String,Object>,Object>> calls=new LinkedHashMap<>();
    public WebReads(WebCatalog catalog, CategoryMapper readerCategoryMapper, TagMapper readerTagMapper, TodoMapper readerTodoMapper, TodoTaskMapper readerTodoTaskMapper, CategoryService serviceCategoryService, TagService serviceTagService, TodoService serviceTodoService, TodoDashboardService serviceTodoDashboardService) {
        this.catalog=catalog;
        calls.put("CategoryMapper.findDetail", params -> {
            var input=new CategoryMapper.FindDetailInput();
            input.setId((java.lang.String)params.get("id"));
            return readerCategoryMapper.findDetail(input);
        });
        calls.put("CategoryMapper.selectAll", params -> {
            return readerCategoryMapper.selectAll();
        });
        calls.put("CategoryMapper.selectTodoDashboardCategories", params -> {
            return readerCategoryMapper.selectTodoDashboardCategories();
        });
        calls.put("CategoryMapper.countTodoDashboardCategories", params -> {
            return readerCategoryMapper.countTodoDashboardCategories();
        });
        calls.put("CategoryMapper.selectTodoDashboardCategoriesMaster", params -> {
            return readerCategoryMapper.selectTodoDashboardCategoriesMaster();
        });
        calls.put("CategoryMapper.countTodoDashboardCategoriesMaster", params -> {
            return readerCategoryMapper.countTodoDashboardCategoriesMaster();
        });
        calls.put("TagMapper.findDetail", params -> {
            var input=new TagMapper.FindDetailInput();
            input.setId((java.lang.String)params.get("id"));
            return readerTagMapper.findDetail(input);
        });
        calls.put("TagMapper.selectAll", params -> {
            return readerTagMapper.selectAll();
        });
        calls.put("TagMapper.selectTodoDashboardTags", params -> {
            return readerTagMapper.selectTodoDashboardTags();
        });
        calls.put("TagMapper.countTodoDashboardTags", params -> {
            return readerTagMapper.countTodoDashboardTags();
        });
        calls.put("TagMapper.selectTodoDashboardTagsMaster", params -> {
            return readerTagMapper.selectTodoDashboardTagsMaster();
        });
        calls.put("TagMapper.countTodoDashboardTagsMaster", params -> {
            return readerTagMapper.countTodoDashboardTagsMaster();
        });
        calls.put("TodoMapper.findDetail", params -> {
            var input=new TodoMapper.FindDetailInput();
            input.setId((java.lang.String)params.get("id"));
            return readerTodoMapper.findDetail(input);
        });
        calls.put("TodoMapper.selectAll", params -> {
            return readerTodoMapper.selectAll();
        });
        calls.put("TodoMapper.selectTodoDashboardOverdue", params -> {
            var input=new TodoMapper.SelectTodoDashboardOverdueInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            input.setToday((java.time.LocalDate)params.get("today"));
            return readerTodoMapper.selectTodoDashboardOverdue(input);
        });
        calls.put("TodoMapper.countTodoDashboardOverdue", params -> {
            var input=new TodoMapper.CountTodoDashboardOverdueInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            input.setToday((java.time.LocalDate)params.get("today"));
            return readerTodoMapper.countTodoDashboardOverdue(input);
        });
        calls.put("TodoMapper.selectTodoDashboardAll", params -> {
            var input=new TodoMapper.SelectTodoDashboardAllInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            return readerTodoMapper.selectTodoDashboardAll(input);
        });
        calls.put("TodoMapper.countTodoDashboardAll", params -> {
            var input=new TodoMapper.CountTodoDashboardAllInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            return readerTodoMapper.countTodoDashboardAll(input);
        });
        calls.put("TodoMapper.findById", params -> {
            var input=new TodoMapper.FindByIdInput();
            input.setId((java.lang.String)params.get("id"));
            return readerTodoMapper.findById(input);
        });
        calls.put("TodoTaskMapper.findById", params -> {
            var input=new TodoTaskMapper.FindByIdInput();
            input.setId((java.lang.String)params.get("id"));
            return readerTodoTaskMapper.findById(input);
        });
        calls.put("TodoTaskMapper.countCompleteTodoNoOpenTask", params -> {
            var input=new TodoTaskMapper.CountCompleteTodoNoOpenTaskInput();
            input.setId((java.lang.String)params.get("id"));
            return readerTodoTaskMapper.countCompleteTodoNoOpenTask(input);
        });
        calls.put("TodoTaskMapper.countClosedHasNoOpenTask", params -> {
            var input=new TodoTaskMapper.CountClosedHasNoOpenTaskInput();
            input.setTodoId((java.lang.String)params.get("todoId"));
            return readerTodoTaskMapper.countClosedHasNoOpenTask(input);
        });

        calls.put("CategoryService.findDetail",params -> serviceCategoryService.findDetail((String)params.get("id")));

        calls.put("TagService.findDetail",params -> serviceTagService.findDetail((String)params.get("id")));

        calls.put("TodoService.findDetail",params -> serviceTodoService.findDetail((String)params.get("id")));

        calls.put("TodoDashboardService.show",params -> serviceTodoDashboardService.show(params));
    }

    public Object read(String call,Map<String,Object> params) {
        var reader=calls.get(call);
        if(reader==null)throw new IllegalArgumentException("未対応の読出し: "+call);
        return normalize(reader.apply(params),catalog.methods.get(call));
    }
    private Object normalize(Object value,Map<String,Object> method) {
        if(value==null||value instanceof Number||value instanceof String||value instanceof Boolean||value instanceof java.time.temporal.TemporalAccessor)return value;
        if(value instanceof List<?> items)return items.stream().map(v->normalize(v,method)).toList();
        if(value instanceof Map<?,?> results){var out=new LinkedHashMap<String,Object>();for(var e:results.entrySet())out.put(e.getKey().toString(),normalize(e.getValue(),catalog.methods.get(e.getKey())));return out;}
        var bean=new BeanWrapperImpl(value);
        var out=new LinkedHashMap<String,Object>();
        // 物理名を Java の名前へ、論理項目を物理列へ結び付けた確定情報で戻す。
        Map<String,Object> row=catalog.rows.values().stream().filter(r->value.getClass().getSimpleName().equals(s(r,"className"))||value.getClass().getSuperclass().getSimpleName().equals(s(r,"className"))).findFirst().orElse(Map.of());
        var parents=new LinkedHashMap<String,Object>();var hidden=new ArrayList<String>();
        for(var column:maps(row.get("columns"))) {
            Object v=bean.getPropertyValue(s(column,"name"));
            for(var mapping:maps(column.get("mapping"))) {
                String logical=s(mapping,"item");if(!logical.isEmpty())out.put(logical,v);
                if(!s(mapping,"role").isEmpty())hidden.add(logical);
                if(s(mapping,"role").equals("parent"))parents.put(s(mapping,"referencesTable"),v);
            }
        }
        if(!parents.isEmpty())out.put("_parents",parents);
        if(!hidden.isEmpty())out.put("_hidden",hidden);
        // アクセス設計にある対応を優先する。Java 名や SQL 名から表示名を推測しない。
        for(var column:maps(map(method.get("raw")).get("columnBindings"))) for(var c:maps(row.get("columns")))
            if(s(c,"source").equals(s(column,"columnId"))) out.put(s(column,"item"),bean.getPropertyValue(s(c,"name")));
        var projections=new ArrayList<Object>();
        for(var p:maps(method.get("properties"))) {
            Object v=bean.getPropertyValue(s(p,"name"));
            Object converted=normalize(v,Map.of());out.put(s(p,"name"),converted);
            if(s(p,"name").startsWith("column"))projections.add(converted);
        }
        if(!projections.isEmpty())out.put("_columns",projections);
        return out;
    }
}
